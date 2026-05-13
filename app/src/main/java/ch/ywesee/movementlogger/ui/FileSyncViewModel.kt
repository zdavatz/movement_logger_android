package ch.ywesee.movementlogger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ch.ywesee.movementlogger.ble.BleSyncService
import ch.ywesee.movementlogger.ble.FileSyncCore
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

data class FileSyncUiState(
    val connection: Connection = Connection.Disconnected,
    val scanning: Boolean = false,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val files: List<RemoteFile> = emptyList(),
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val savedPaths: Map<String, String> = emptyMap(),
    val listing: Boolean = false,
    val log: List<String> = emptyList(),
    val sessionDurationSeconds: Int = 1800,  // 30-min default, matches desktop
    val sessionRunning: SessionRunning? = null,
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
    fun download(file: RemoteFile) = FileSyncCore.download(file)
    fun delete(file: RemoteFile) = FileSyncCore.delete(file)
    fun stopLog() = FileSyncCore.stopLog()
    fun setSessionDuration(seconds: Int) = FileSyncCore.setSessionDuration(seconds)
    fun startSession() = FileSyncCore.startSession()
    fun clearSession() = FileSyncCore.clearSession()
}

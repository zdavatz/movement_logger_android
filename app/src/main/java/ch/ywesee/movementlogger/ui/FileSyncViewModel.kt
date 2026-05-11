package ch.ywesee.movementlogger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.ywesee.movementlogger.ble.BleClient
import ch.ywesee.movementlogger.ble.BleCmd
import ch.ywesee.movementlogger.ble.BleEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

class FileSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = BleClient(app.applicationContext)
    private val _state = MutableStateFlow(FileSyncUiState())
    val state: StateFlow<FileSyncUiState> = _state.asStateFlow()

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        viewModelScope.launch {
            ble.events.collect { onEvent(it) }
        }
    }

    override fun onCleared() {
        ble.close()
        super.onCleared()
    }

    // ---------------- UI intents -------------------------------------------

    fun scan() {
        _state.update { it.copy(scanning = true, discovered = emptyList()) }
        log("scan…")
        ble.send(BleCmd.Scan)
    }

    fun connect(address: String) {
        _state.update { it.copy(connection = Connection.Connecting) }
        log("connect $address")
        ble.send(BleCmd.Connect(address))
    }

    fun disconnect() {
        log("disconnect")
        ble.send(BleCmd.Disconnect)
    }

    fun listFiles() {
        _state.update { it.copy(listing = true, files = emptyList()) }
        log("LIST")
        ble.send(BleCmd.List)
    }

    fun download(file: RemoteFile) {
        _state.update {
            it.copy(downloads = it.downloads + (file.name to DownloadProgress(0, file.size)))
        }
        log("READ ${file.name} (${file.size} B)")
        ble.send(BleCmd.Read(file.name, file.size))
    }

    fun delete(file: RemoteFile) {
        log("DELETE ${file.name}")
        ble.send(BleCmd.Delete(file.name))
    }

    fun stopLog() {
        log("STOP_LOG")
        ble.send(BleCmd.StopLog)
    }

    fun setSessionDuration(seconds: Int) {
        val clamped = seconds.coerceIn(1, 86_400)  // 1 s .. 24 h, same range as desktop
        _state.update { it.copy(sessionDurationSeconds = clamped) }
    }

    fun startSession() {
        val dur = _state.value.sessionDurationSeconds
        log("START_LOG $dur s — box rebooting to LOG mode")
        ble.send(BleCmd.StartLog(dur))
        // Firmware NVIC_SystemReset's ~50 ms after START_LOG, so the BLE
        // link dies abruptly without LL_TERMINATE_IND. Send an explicit
        // Disconnect right after to tear down host state proactively;
        // either way the worker ends up Idle.
        ble.send(BleCmd.Disconnect)
        _state.update {
            it.copy(
                sessionRunning = SessionRunning(
                    startedAtMillis = System.currentTimeMillis(),
                    durationSeconds = dur,
                ),
                files = emptyList(),
                downloads = emptyMap(),
                listing = false,
            )
        }
    }

    fun clearSession() {
        if (_state.value.sessionRunning != null) {
            log("LOG session deadline reached — box should be advertising again")
            _state.update { it.copy(sessionRunning = null) }
        }
    }

    // ---------------- Event handling ---------------------------------------

    private fun onEvent(e: BleEvent) {
        when (e) {
            is BleEvent.Status -> log(e.msg)
            is BleEvent.Error -> log("ERROR: ${e.msg}")
            is BleEvent.Discovered -> _state.update { s ->
                if (s.discovered.any { it.address == e.address }) s
                else s.copy(discovered = s.discovered + DiscoveredDevice(e.address, e.name, e.rssi))
            }
            BleEvent.ScanStopped -> {
                _state.update { it.copy(scanning = false) }
                log("scan stopped (${_state.value.discovered.size} found)")
            }
            BleEvent.Connected -> {
                _state.update { it.copy(connection = Connection.Connected) }
                log("connected")
            }
            BleEvent.Disconnected -> {
                _state.update {
                    it.copy(
                        connection = Connection.Disconnected,
                        files = emptyList(),
                        listing = false,
                        downloads = emptyMap(),
                    )
                }
                log("disconnected")
            }
            is BleEvent.ListEntry -> _state.update { s ->
                s.copy(files = s.files + RemoteFile(e.name, e.size))
            }
            BleEvent.ListDone -> {
                _state.update { it.copy(listing = false) }
                log("LIST done (${_state.value.files.size} files)")
            }
            is BleEvent.ReadStarted -> Unit
            is BleEvent.ReadProgress -> _state.update { s ->
                val cur = s.downloads[e.name] ?: return@update s
                s.copy(downloads = s.downloads + (e.name to cur.copy(bytesDone = e.bytesDone)))
            }
            is BleEvent.ReadDone -> {
                val path = saveFile(e.name, e.content)
                _state.update { s ->
                    s.copy(
                        downloads = s.downloads - e.name,
                        savedPaths = s.savedPaths + (e.name to path),
                    )
                }
                log("saved ${e.name} → $path")
            }
            is BleEvent.DeleteDone -> {
                _state.update { s -> s.copy(files = s.files.filterNot { it.name == e.name }) }
                log("deleted ${e.name}")
            }
        }
    }

    private fun saveFile(name: String, bytes: ByteArray): String {
        val dir: File = getApplication<Application>().getExternalFilesDir(null)
            ?: getApplication<Application>().filesDir
        val outFile = File(dir, name)
        outFile.writeBytes(bytes)
        return outFile.absolutePath
    }

    private fun log(msg: String) {
        val stamp = tsFmt.format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $msg").takeLast(MAX_LOG_LINES)) }
    }

    companion object {
        private const val MAX_LOG_LINES = 200
    }
}

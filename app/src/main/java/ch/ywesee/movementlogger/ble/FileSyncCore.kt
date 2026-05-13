package ch.ywesee.movementlogger.ble

import android.annotation.SuppressLint
import android.content.Context
import ch.ywesee.movementlogger.ui.Connection
import ch.ywesee.movementlogger.ui.DiscoveredDevice
import ch.ywesee.movementlogger.ui.DownloadProgress
import ch.ywesee.movementlogger.ui.FileSyncUiState
import ch.ywesee.movementlogger.ui.RemoteFile
import ch.ywesee.movementlogger.ui.SessionRunning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide singleton that owns the [BleClient] and the FileSync UI state.
 *
 * Moved out of [ch.ywesee.movementlogger.ui.FileSyncViewModel] so the BLE
 * worker, the in-flight READ buffer, and the StateFlow survive Activity
 * recreation and — when paired with [BleSyncService] — the app being put
 * in the background. The ViewModel is now a thin observer/forwarder.
 *
 * Initialisation is idempotent: the first caller (typically
 * [BleSyncService.onCreate] or the ViewModel ctor, whichever lands first)
 * sets up the BleClient and the event-collection job. All later [ensureInit]
 * calls are no-ops.
 */
@SuppressLint("StaticFieldLeak")  // Holds applicationContext only — safe.
object FileSyncCore {

    private val _state = MutableStateFlow(FileSyncUiState())
    val state: StateFlow<FileSyncUiState> = _state.asStateFlow()

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var ble: BleClient? = null
    private var collectJob: Job? = null
    private var appContext: Context? = null
    private var listener: Listener? = null

    /** Hook for [BleSyncService] to react to state transitions. */
    interface Listener {
        fun onStateChanged(state: FileSyncUiState)
    }

    fun ensureInit(context: Context) {
        if (ble != null) return
        appContext = context.applicationContext
        ble = BleClient(context.applicationContext)
        collectJob = scope.launch {
            ble!!.events.collect { onEvent(it) }
        }
    }

    fun setListener(l: Listener?) {
        listener = l
        l?.onStateChanged(_state.value)
    }

    // ---------------- UI intents ------------------------------------------

    fun scan() {
        _state.update { it.copy(scanning = true, discovered = emptyList()) }
        log("scan…")
        ble?.send(BleCmd.Scan)
    }

    fun connect(address: String) {
        _state.update { it.copy(connection = Connection.Connecting) }
        log("connect $address")
        ble?.send(BleCmd.Connect(address))
    }

    fun disconnect() {
        log("disconnect")
        ble?.send(BleCmd.Disconnect)
    }

    fun listFiles() {
        _state.update { it.copy(listing = true, files = emptyList()) }
        log("LIST")
        ble?.send(BleCmd.List)
    }

    fun download(file: RemoteFile) {
        _state.update {
            it.copy(downloads = it.downloads + (file.name to DownloadProgress(0, file.size)))
        }
        log("READ ${file.name} (${file.size} B)")
        ble?.send(BleCmd.Read(file.name, file.size))
    }

    fun delete(file: RemoteFile) {
        log("DELETE ${file.name}")
        ble?.send(BleCmd.Delete(file.name))
    }

    fun stopLog() {
        log("STOP_LOG")
        ble?.send(BleCmd.StopLog)
    }

    fun setSessionDuration(seconds: Int) {
        val clamped = seconds.coerceIn(1, 86_400)
        _state.update { it.copy(sessionDurationSeconds = clamped) }
    }

    fun startSession() {
        val dur = _state.value.sessionDurationSeconds
        log("START_LOG $dur s — box rebooting to LOG mode")
        ble?.send(BleCmd.StartLog(dur))
        ble?.send(BleCmd.Disconnect)
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

    // ---------------- Event handling --------------------------------------

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
        listener?.onStateChanged(_state.value)
    }

    private fun saveFile(name: String, bytes: ByteArray): String {
        val ctx = appContext ?: return ""
        val dir: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val outFile = File(dir, name)
        outFile.writeBytes(bytes)
        return outFile.absolutePath
    }

    private fun log(msg: String) {
        val stamp = tsFmt.format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $msg").takeLast(MAX_LOG_LINES)) }
        listener?.onStateChanged(_state.value)
    }

    /** True while BLE work is active — used by [BleSyncService] to decide
     *  whether to stay foreground or stop itself. */
    fun isBusy(): Boolean {
        val s = _state.value
        return s.connection != Connection.Disconnected ||
            s.scanning ||
            s.listing ||
            s.downloads.isNotEmpty()
    }

    private const val MAX_LOG_LINES = 200
}

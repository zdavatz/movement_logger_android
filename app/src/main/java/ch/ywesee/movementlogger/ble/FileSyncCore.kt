package ch.ywesee.movementlogger.ble

import android.annotation.SuppressLint
import android.content.Context
import ch.ywesee.movementlogger.ui.Connection
import ch.ywesee.movementlogger.ui.DiscoveredDevice
import ch.ywesee.movementlogger.ui.DownloadProgress
import ch.ywesee.movementlogger.ui.FileSyncUiState
import ch.ywesee.movementlogger.ui.LivePoint
import ch.ywesee.movementlogger.ui.LiveState
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

    // ---- Sync-state (port of desktop sync_db.rs + issues #3/#4) ----
    /** BLE MAC of the connected box, captured at Connect time (desktop
     *  captures the peripheral id at Connect too). The DB partition key —
     *  sync history is per-box. */
    private var connectedBoxId: String? = null
    /** Opened once in [ensureInit]; null only if SQLite itself failed
     *  (then sync degrades to "nothing is synced" vs crashing the tab). */
    private var syncDb: SyncDb? = null
    /** Set by [syncNow], consumed by the next [BleEvent.ListDone] so the
     *  auto-LIST on connect never triggers a sync (desktop sync flag). */
    private var syncPending = false
    /** Files this sync still has to pull, oldest-first; drained serially
     *  because the BLE worker is single-op (one READ at a time). */
    private val syncQueue = ArrayDeque<RemoteFile>()
    /** Name of the file the *sync* is currently pulling (null for a manual
     *  download), so [BleEvent.ReadDone] knows whether to advance. */
    private var syncInFlight: String? = null
    /** LIST-reported size per in-flight download. The DB key is
     *  `(box, name, size)` and `size` must be the LIST size (matches what
     *  a later isSynced check compares), not the received byte count. */
    private val pendingSizes = HashMap<String, Long>()

    /** Hook for [BleSyncService] to react to state transitions. */
    interface Listener {
        fun onStateChanged(state: FileSyncUiState)
    }

    fun ensureInit(context: Context) {
        if (ble != null) return
        appContext = context.applicationContext
        syncDb = SyncDb.open(context.applicationContext)
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
        connectedBoxId = address
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
        pendingSizes[file.name] = file.size
        log("READ ${file.name} (${file.size} B)")
        ble?.send(BleCmd.Read(file.name, file.size))
    }

    /**
     * "Sync now" — pull every session file on the box that isn't already
     * recorded locally, and remember what was pulled. Port of the desktop
     * Sync tab's distinct-from-manual-transfer button (issue #3).
     * Additive only: never issues DELETE.
     */
    fun syncNow() {
        if (_state.value.connection != Connection.Connected) return
        if (connectedBoxId == null || syncDb == null) {
            log("ERROR: sync DB unavailable — sync disabled")
            _state.update { it.copy(syncStatus = "Sync unavailable (no local DB)") }
            return
        }
        syncPending = true
        syncQueue.clear()
        syncInFlight = null
        _state.update {
            it.copy(syncing = true, listing = true, files = emptyList(),
                    syncStatus = "Sync: listing…")
        }
        log("Sync now — LIST")
        ble?.send(BleCmd.List)
    }

    /**
     * Session-data filter — same predicate as the Sync tab's grouping and
     * the desktop's auto-sync set (Sens/Gps/Bat/Mic; AppleDouble excluded).
     * Only these are auto-pulled by [syncNow]; FW_INFO / CHK / error logs
     * stay manual-only.
     */
    private fun isSensorData(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("._")) return false
        return (n.startsWith("sens") && n.endsWith(".csv")) ||
            (n.startsWith("gps")  && n.endsWith(".csv")) ||
            (n.startsWith("bat")  && n.endsWith(".csv")) ||
            (n.startsWith("mic")  && n.endsWith(".wav"))
    }

    /**
     * Diff the just-LISTed files against the sync DB and enqueue the
     * session files we haven't pulled. Mirrors the desktop's ListDone
     * sync handler: filter to session data, skip anything isSynced,
     * enqueue the rest onto the existing serial download path.
     */
    private fun runSyncDiff() {
        val box = connectedBoxId
        val db = syncDb
        if (box == null || db == null) {
            _state.update { it.copy(syncing = false, syncStatus = "Sync unavailable (no local DB)") }
            return
        }
        val candidates = _state.value.files.filter { isSensorData(it.name) }
        val fresh = candidates.filter { !db.isSynced(box, it.name, it.size) }
        val already = candidates.size - fresh.size
        if (fresh.isEmpty()) {
            _state.update {
                it.copy(syncing = false,
                        syncStatus = "Sync: up to date ($already already synced)")
            }
            log("Sync: up to date — $already already synced, 0 new")
            return
        }
        syncQueue.clear()
        syncQueue.addAll(fresh)
        _state.update {
            it.copy(syncStatus =
                "Sync: ${fresh.size} new, $already already synced — downloading…")
        }
        log("Sync: ${fresh.size} new, $already already synced — downloading")
        pumpSyncQueue()
    }

    /**
     * Pull the next queued file. The BLE worker is single-op, so sync
     * downloads must be strictly serial — the next READ is only issued
     * from [BleEvent.ReadDone] of the previous one.
     */
    private fun pumpSyncQueue() {
        val next = syncQueue.removeFirstOrNull()
        if (next == null) {
            if (_state.value.syncing) {
                _state.update { it.copy(syncing = false, syncStatus = "Sync: complete") }
                log("Sync: complete")
            }
            return
        }
        syncInFlight = next.name
        download(next)
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
            is BleEvent.Error -> {
                log("ERROR: ${e.msg}")
                // A BLE error mid-sync would otherwise strand the queue
                // (syncInFlight never clears). Abort cleanly so the next
                // "Sync now" starts fresh; the size key means a partial
                // file is just re-pulled next time (desktop-equivalent).
                if (_state.value.syncing) {
                    syncPending = false
                    syncQueue.clear()
                    syncInFlight = null
                    _state.update {
                        it.copy(syncing = false,
                                syncStatus = "Sync aborted (BLE error) — try again")
                    }
                }
            }
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
                // Drop the live-stream buffers too — the box may resume
                // with a fresh timestamp axis on reconnect, and a stale
                // sparkline would falsely suggest the stream is still alive.
                connectedBoxId = null
                syncPending = false
                syncQueue.clear()
                syncInFlight = null
                pendingSizes.clear()
                _state.update {
                    it.copy(
                        connection = Connection.Disconnected,
                        files = emptyList(),
                        listing = false,
                        syncing = false,
                        downloads = emptyMap(),
                        live = LiveState(),
                    )
                }
                liveT0Ms = null
                log("disconnected")
            }
            is BleEvent.ListEntry -> _state.update { s ->
                s.copy(files = s.files + RemoteFile(e.name, e.size))
            }
            BleEvent.ListDone -> {
                _state.update { it.copy(listing = false) }
                log("LIST done (${_state.value.files.size} files)")
                if (syncPending) {
                    syncPending = false
                    runSyncDiff()
                }
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
                // Register every successful save — manual *and*
                // sync-driven — so a later "Sync now" skips it regardless
                // of how it landed (desktop: "Manual downloads also
                // register in the DB").
                val size = pendingSizes.remove(e.name) ?: e.content.size.toLong()
                val box = connectedBoxId
                if (path.isNotEmpty() && box != null) {
                    syncDb?.markSynced(box, e.name, size, path)
                }
                if (syncInFlight == e.name) {
                    syncInFlight = null
                    pumpSyncQueue()
                }
            }
            is BleEvent.DeleteDone -> {
                _state.update { s -> s.copy(files = s.files.filterNot { it.name == e.name }) }
                log("deleted ${e.name}")
            }
            is BleEvent.Sample -> onSample(e.sample)
        }
        listener?.onStateChanged(_state.value)
    }

    /** First-sample box-timestamp; sparkline X axis is `(s.timestampMs - liveT0Ms) / 1000`. */
    private var liveT0Ms: Long? = null

    private fun onSample(s: ch.ywesee.movementlogger.ble.LiveSample) {
        val t0 = liveT0Ms ?: run { liveT0Ms = s.timestampMs; s.timestampMs }
        val dt = (s.timestampMs - t0) / 1000.0
        val accG = s.accMagnitudeG()
        val presHpa = s.pressurePa / 100.0
        _state.update { st ->
            val acc = (st.live.accHistory + LivePoint(dt, accG)).takeLast(LIVE_HISTORY_LEN)
            val pres = (st.live.pressureHistory + LivePoint(dt, presHpa)).takeLast(LIVE_HISTORY_LEN)
            st.copy(
                live = st.live.copy(
                    latestSample = s,
                    latestSampleAtMs = System.currentTimeMillis(),
                    sampleCount = st.live.sampleCount + 1,
                    accHistory = acc,
                    pressureHistory = pres,
                    streamCapable = true,
                )
            )
        }
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
            s.syncing ||
            s.downloads.isNotEmpty()
    }

    private const val MAX_LOG_LINES = 200
    /** Bounded rolling buffer for the Live tab sparklines. 120 × 2 s = 4 min. */
    private const val LIVE_HISTORY_LEN = 120
}

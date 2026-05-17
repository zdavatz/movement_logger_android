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
    /** "Keep synced" 30 s poll loop (desktop v0.0.14). The pass only
     *  fetches each file's new tail; survives disconnect (its tick
     *  guards on Connected so it resumes after a reconnect). */
    private var keepSyncedJob: Job? = null

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
        // Live-mirror: resume/grow from whatever is already on disk. The
        // firmware seeks to `offset`, so an interrupted file continues
        // and a grown log only fetches its new tail (desktop v0.0.14).
        val offset = mirrorOffset(file.name, file.size)
        if (file.size > 0 && offset >= file.size) {
            log("${file.name} already mirrored (${file.size} B)")
            return
        }
        _state.update {
            it.copy(downloads = it.downloads + (file.name to DownloadProgress(offset, file.size)))
        }
        log("READ ${file.name} @$offset/${file.size} B")
        ble?.send(BleCmd.Read(file.name, file.size, offset))
    }

    /**
     * "Sync now" — pull every session file on the box that isn't already
     * recorded locally, and remember what was pulled. Port of the desktop
     * Sync tab's distinct-from-manual-transfer button (issue #3).
     * Additive only: never issues DELETE.
     */
    fun syncNow() = startSyncPass("Sync now")

    /**
     * "Keep synced" — while connected and idle, re-run a sync pass every
     * 30 s so a continuously-growing log keeps mirrored (desktop
     * v0.0.14). The pass itself only fetches each file's new tail.
     */
    fun setKeepSynced(on: Boolean) {
        _state.update { it.copy(keepSynced = on) }
        log("Keep synced ${if (on) "on" else "off"}")
        keepSyncedJob?.cancel()
        keepSyncedJob = null
        if (!on) return
        keepSyncedJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(SYNC_POLL_INTERVAL_MS)
                val s = _state.value
                if (s.keepSynced && s.connection == Connection.Connected &&
                    !s.listing && !s.syncing && s.downloads.isEmpty() &&
                    syncInFlight == null
                ) {
                    startSyncPass("Keep synced")
                }
            }
        }
    }

    /**
     * Begin one sync pass: fresh LIST, then the diff runs in the
     * [BleEvent.ListDone] handler (gated on `syncPending` so the auto-
     * LIST on connect never starts a sync by itself). Shared by the
     * button and the continuous loop (desktop `start_sync_pass`).
     */
    private fun startSyncPass(reason: String) {
        if (_state.value.connection != Connection.Connected) return
        if (connectedBoxId == null) {
            _state.update { it.copy(syncStatus = "Sync: no box id (reconnect and retry)") }
            return
        }
        syncPending = true
        syncQueue.clear()
        syncInFlight = null
        _state.update {
            it.copy(syncing = true, listing = true, files = emptyList(),
                    syncStatus = "Sync: listing SD card…")
        }
        log("$reason — LIST")
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
     * Decide what to fetch by **local mirror size vs box size**, not a
     * DB lookup (desktop v0.0.14). That's what makes a continuously-
     * growing log work: each pass fetches only the new tail (offset =
     * local size) instead of re-pulling the whole file, so no single
     * big file can starve GPS/BAT in the serial queue either. The
     * SQLite DB is now an audit log, not the fetch decision.
     */
    private fun runSyncDiff() {
        val candidates = _state.value.files.filter { isSensorData(it.name) }
        val fetch = ArrayList<RemoteFile>()
        var upToDate = 0
        for (f in candidates) {
            // local < box → grow/resume; local > box → rotated
            // (mirrorOffset resets it). Either way, fetch.
            if (mirrorLocalSize(f.name) == f.size) upToDate++ else fetch.add(f)
        }
        if (fetch.isEmpty()) {
            _state.update {
                it.copy(syncing = false,
                        syncStatus = "Sync: up to date ($upToDate files)")
            }
            log("Sync: up to date — $upToDate files")
            return
        }
        syncQueue.clear()
        syncQueue.addAll(fetch)
        _state.update {
            it.copy(syncStatus = "Sync: fetching ${fetch.size} ($upToDate up to date)…")
        }
        log("Sync: fetching ${fetch.size}, $upToDate up to date")
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
        _state.update { it.copy(deleteError = null) }  // clear stale rejection
        log("DELETE ${file.name}")
        ble?.send(BleCmd.Delete(file.name))
    }

    fun dismissDeleteError() {
        _state.update { it.copy(deleteError = null) }
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
                // Surface DELETE rejections prominently — the box refuses
                // some Debug rows (8.3-name miss -> NOT_FOUND, >15 chars
                // -> BAD_REQUEST, logging active -> BUSY). Without this it
                // only hits the log and looks like the tap did nothing (#7).
                if (e.msg.startsWith("DELETE ")) {
                    _state.update { it.copy(deleteError = e.msg) }
                }
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
                // Keep the "Keep synced" toggle + poll job alive across a
                // disconnect — its tick guards on Connected, so it simply
                // resumes after a reconnect (sets up Stage 3 auto-resume).
                _state.update {
                    it.copy(
                        connection = Connection.Disconnected,
                        files = emptyList(),
                        listing = false,
                        syncing = false,
                        downloads = emptyMap(),
                        live = LiveState(),
                        deleteError = null,
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
                // Append the streamed segment into the local mirror at
                // the resume offset (desktop v0.0.14). The mirror file
                // *is* the saved download — always a valid prefix.
                val (path, localSize) = appendMirror(e.name, e.base, e.content)
                _state.update { s ->
                    s.copy(
                        downloads = s.downloads - e.name,
                        savedPaths = s.savedPaths + (e.name to path),
                    )
                }
                log("saved ${e.name} → $path ($localSize B)")
                // DB is an audit log now (not the fetch decision): record
                // that this file reached this size, saved here.
                val box = connectedBoxId
                if (path.isNotEmpty() && box != null) {
                    syncDb?.markSynced(box, e.name, localSize, path)
                }
                if (syncInFlight == e.name) {
                    syncInFlight = null
                    pumpSyncQueue()
                }
            }
            is BleEvent.DeleteDone -> {
                _state.update { s ->
                    s.copy(files = s.files.filterNot { it.name == e.name },
                           deleteError = null)
                }
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

    // ---------------- Live mirror (desktop v0.0.14) -----------------------
    //
    // The local file `<filesDir>/<name>` *is* the running mirror — we
    // accumulate straight into it (no .part/rename). The box's logs grow
    // continuously, so "done" is a moving target; what matters is the local
    // file is always a valid prefix and we only fetch bytes we don't have.
    // Local size is the single source of truth for the resume/grow offset.
    // The DB is a separate audit log.

    private fun mirrorDir(): File =
        (appContext?.getExternalFilesDir(null) ?: appContext?.filesDir)!!

    private fun mirrorFile(name: String): File = File(mirrorDir(), name)

    /** Current local mirror length, 0 if absent. */
    private fun mirrorLocalSize(name: String): Long {
        val f = mirrorFile(name)
        return if (f.exists()) f.length() else 0L
    }

    /**
     * Resume offset given the box's current size:
     * missing → 0; local ≤ box → local (resume/grow); local > box → the
     * file rotated (name reused, box file shorter) → drop local, 0.
     */
    private fun mirrorOffset(name: String, boxSize: Long): Long {
        val f = mirrorFile(name)
        if (!f.exists()) return 0L
        val len = f.length()
        if (len <= boxSize) return len
        f.delete()  // rotated/stale
        return 0L
    }

    /**
     * Append a streamed segment at `base` (creating the file). If the
     * file length doesn't match `base` the resume is misaligned —
     * realign to `base` so we never interleave a corrupt prefix.
     * Returns (absolutePath, new local size).
     */
    private fun appendMirror(name: String, base: Long, bytes: ByteArray): Pair<String, Long> {
        return try {
            val f = mirrorFile(name)
            f.parentFile?.mkdirs()
            java.io.RandomAccessFile(f, "rw").use { raf ->
                if (raf.length() != base) raf.setLength(base.coerceAtMost(raf.length()))
                raf.seek(raf.length())
                raf.write(bytes)
                Pair(f.absolutePath, raf.length())
            }
        } catch (ex: Exception) {
            log("ERROR: mirror $name: ${ex.message}")
            Pair("", base + bytes.size)
        }
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

    /** "Keep synced" idle poll interval (desktop SYNC_POLL_INTERVAL). */
    private const val SYNC_POLL_INTERVAL_MS = 30_000L
    private const val MAX_LOG_LINES = 200
    /** Bounded rolling buffer for the Live tab sparklines. 120 × 2 s = 4 min. */
    private const val LIVE_HISTORY_LEN = 120
}

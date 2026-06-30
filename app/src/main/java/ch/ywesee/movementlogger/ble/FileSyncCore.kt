package ch.ywesee.movementlogger.ble

import android.annotation.SuppressLint
import android.content.Context
import ch.ywesee.movementlogger.data.PublicMirror
import ch.ywesee.movementlogger.sync.AgentConfig
import ch.ywesee.movementlogger.sync.BackgroundSync
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
    /** File log keeps the date too — one file spans many sessions/days. */
    private val fileTsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var ble: BleClient? = null
    private var collectJob: Job? = null
    private var appContext: Context? = null
    private var listener: Listener? = null
    /** Side-channel: publishes every appended chunk into
     *  `Download/MovementLogger/` so Google Files / any file manager can
     *  see synced data. Best-effort; failures here never break the
     *  canonical internal mirror under `getExternalFilesDir(null)`. */
    private var publicMirror: PublicMirror? = null

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
    /**
     * Name of the file the *sync* is currently pulling (null for a manual
     * download). Mirrored into [FileSyncUiState.syncInFlight] so the
     * progress card can show the in-flight file's per-file row; also
     * consulted in [BleEvent.ReadDone] to advance the queue.
     */
    private var syncInFlight: String?
        get() = _state.value.syncInFlight
        set(value) { _state.update { it.copy(syncInFlight = value) } }
    /** "Keep synced" 30 s poll loop (desktop v0.0.14). The pass only
     *  fetches each file's new tail; survives disconnect (its tick
     *  guards on Connected so it resumes after a reconnect). */
    private var keepSyncedJob: Job? = null
    /**
     * Manual (button-tap) download queue. The BLE worker is single-op, so
     * tapping Download on a second file while the first is still streaming
     * used to be rejected by the worker ("another op in flight") and the
     * row stuck forever. Instead we queue taps here and drain them strictly
     * one-at-a-time — the next READ is issued only when the worker goes
     * idle (from [pumpManualQueue], called on ReadDone / ListDone). Distinct
     * from [syncQueue]: that's the auto-sync pass; this is explicit taps.
     */
    private val manualQueue = ArrayDeque<RemoteFile>()
    /**
     * True while a brief single-op command that isn't reflected in the UI
     * state (DELETE, GET/SET log mode) is outstanding on the BLE worker.
     * [pumpManualQueue] consults it so a Delete-then-Download or mode-toggle-
     * then-Download double-tap doesn't issue a READ the worker would reject.
     * Set when such a command is sent; cleared on its completion event or
     * any Error (every op-termination path emits one), and on Disconnect.
     */
    private var briefOpInFlight = false

    /** Hook for [BleSyncService] to react to state transitions. */
    interface Listener {
        fun onStateChanged(state: FileSyncUiState)
    }

    fun ensureInit(context: Context) {
        if (ble != null) return
        appContext = context.applicationContext
        syncDb = SyncDb.open(context.applicationContext)
        publicMirror = PublicMirror(context.applicationContext)
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
        // Persist + (re-)arm the background sync now that we know which box
        // is "ours". Matches desktop `SyncCore::persist_config` on Connect.
        appContext?.let { ctx ->
            AgentConfig.setBoxId(ctx, address)
            BackgroundSync.refresh(ctx)
        }
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

    /**
     * Manual Download tap. Enqueues the file and drains the queue serially
     * — the BLE worker is single-op, so two files can never be in flight at
     * once. Tapping a second file while a big one streams now waits its
     * turn instead of getting wedged.
     */
    fun download(file: RemoteFile) {
        // Already mirrored? Nothing to do (skip even queueing).
        val offset = mirrorOffset(file.name, file.size)
        if (file.size > 0 && offset >= file.size) {
            log("${file.name} already mirrored (${file.size} B)")
            return
        }
        // Dedupe: ignore a re-tap of a file that's already downloading or
        // already queued.
        if (_state.value.downloads.containsKey(file.name) ||
            manualQueue.any { it.name == file.name }
        ) {
            return
        }
        manualQueue.addLast(file)
        _state.update { it.copy(queuedDownloads = manualQueue.map { f -> f.name }) }
        log("queued ${file.name} (${manualQueue.size} waiting)")
        pumpManualQueue()
    }

    /**
     * Issue the next queued manual download iff the worker is idle — no
     * LIST, no READ (manual or sync) in flight, no firmware upload, and
     * connected. Called after each tap and whenever an op completes
     * ([BleEvent.ReadDone] / [BleEvent.ListDone]). The idle guard is what
     * keeps downloads strictly serial and collision-free.
     */
    private fun pumpManualQueue() {
        val s = _state.value
        if (s.connection != Connection.Connected) return
        if (s.listing || s.downloads.isNotEmpty() || syncInFlight != null ||
            s.fwUpload != null || briefOpInFlight
        ) return
        val next = manualQueue.removeFirstOrNull() ?: return
        _state.update { it.copy(queuedDownloads = manualQueue.map { f -> f.name }) }
        startRead(next)
    }

    /**
     * Actually start a READ: record the resume offset, show the progress
     * row, send the command. Shared by the manual queue ([pumpManualQueue])
     * and the auto-sync queue ([pumpSyncQueue]); callers guarantee the
     * worker is idle, so this never collides.
     */
    private fun startRead(file: RemoteFile) {
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
        // Persist + reconcile the background WorkManager schedule. Gating
        // also depends on log mode + boxId — [BackgroundSync.refresh] reads
        // [AgentConfig.active] for the actual cancel/enqueue decision.
        appContext?.let { ctx ->
            AgentConfig.setKeepSynced(ctx, on)
            BackgroundSync.refresh(ctx)
        }
        keepSyncedJob?.cancel()
        keepSyncedJob = null
        if (!on) return
        keepSyncedJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(SYNC_POLL_INTERVAL_MS)
                val s = _state.value
                // `transferInterrupted` is true between a mid-READ drop
                // and the auto-reconnect succeeding. During that window
                // [BleClient.armReconnect] has silently torn the link
                // down (emitEvent=false), so the UI still sees
                // `Connected` but `cmdChar` is nil — any LIST/READ here
                // would fail with "FileCmd characteristic missing".
                // Skip until reconnect clears the flag; the Connected
                // handler re-runs `startSyncPass` with reason "Resume"
                // when the link returns, so nothing is lost.
                if (s.keepSynced && s.connection == Connection.Connected &&
                    !s.listing && !s.syncing && s.downloads.isEmpty() &&
                    syncInFlight == null && !s.transferInterrupted
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
        _state.update {
            it.copy(
                syncing = true, listing = true, files = emptyList(),
                syncStatus = "Sync: listing SD card…",
                syncInFlight = null,
                // The pass totals are filled in once LIST resolves and
                // the diff knows what to fetch — bar is empty until then.
                syncPassTotal = 0,
                syncQueueRemaining = 0,
                syncPassTotalBytes = 0,
                syncPassCompletedBytes = 0,
            )
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
                it.copy(
                    syncing = false,
                    syncStatus = "Sync: up to date ($upToDate files)",
                    syncPassTotal = 0,
                    syncQueueRemaining = 0,
                    syncPassTotalBytes = 0,
                    syncPassCompletedBytes = 0,
                )
            }
            log("Sync: up to date — $upToDate files")
            return
        }
        syncQueue.clear()
        syncQueue.addAll(fetch)
        val totalBytes = fetch.sumOf { it.size }
        _state.update {
            it.copy(
                syncStatus = "Sync: fetching ${fetch.size} ($upToDate up to date)…",
                syncPassTotal = fetch.size,
                syncQueueRemaining = fetch.size,
                syncPassTotalBytes = totalBytes,
                // Accounting: `syncPassCompletedBytes` only counts files
                // whose READ has fully completed in THIS pass. The in-
                // flight file's contribution comes from
                // `downloads[name].bytesDone` (which starts at the
                // mirror baseline = resume offset, so already-on-disk
                // bytes count as soon as the file becomes in-flight).
                syncPassCompletedBytes = 0,
            )
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
                _state.update {
                    it.copy(
                        syncing = false,
                        syncStatus = "Sync: complete",
                        syncPassTotal = 0,
                        syncQueueRemaining = 0,
                        syncPassTotalBytes = 0,
                        syncPassCompletedBytes = 0,
                    )
                }
                log("Sync: complete")
            }
            return
        }
        _state.update { it.copy(syncQueueRemaining = syncQueue.size) }
        syncInFlight = next.name
        startRead(next)
    }

    fun delete(file: RemoteFile) {
        _state.update { it.copy(deleteError = null) }  // clear stale rejection
        briefOpInFlight = true
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
        // Current firmware does not reboot on START_LOG — it opens a
        // session and auto-stops after `dur` s, the link stays up. Only
        // meaningful in manual mode (in auto the box is already logging).
        log("START_LOG $dur s")
        ble?.send(BleCmd.StartLog(dur))
        _state.update {
            it.copy(
                sessionRunning = SessionRunning(
                    startedAtMillis = System.currentTimeMillis(),
                    durationSeconds = dur,
                ),
            )
        }
    }

    /** Persist the box log-mode and remember it locally. */
    fun setLogMode(manual: Boolean) {
        briefOpInFlight = true
        log("SET_MODE ${if (manual) "manual" else "auto"}")
        ble?.send(BleCmd.SetLogMode(manual))
    }

    /**
     * Read a firmware `.bin` from the SAF content Uri, compute its SHA-256,
     * and kick off the FW_BEGIN → FW_DATA… → FW_COMMIT upload (desktop/iOS
     * OTA peer). The read + digest run on a background coroutine so a big
     * image doesn't block the caller; the actual BLE streaming is driven by
     * the single-op [BleClient] state machine.
     */
    fun uploadFirmware(context: Context, uri: android.net.Uri) {
        val ctx = context.applicationContext
        scope.launch {
            val name = displayName(ctx, uri) ?: "firmware.bin"
            val image = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (ex: Exception) {
                log("ERROR: read $name: ${ex.message}")
                null
            }
            if (image == null || image.isEmpty()) {
                _state.update {
                    it.copy(fwUploadResult = ch.ywesee.movementlogger.ui.FwUploadResult(
                        false, "couldn't read $name (empty or unreadable)"))
                }
                return@launch
            }
            val sha = try {
                java.security.MessageDigest.getInstance("SHA-256").digest(image)
            } catch (ex: Exception) {
                log("ERROR: SHA-256 $name: ${ex.message}")
                _state.update {
                    it.copy(fwUploadResult = ch.ywesee.movementlogger.ui.FwUploadResult(
                        false, "SHA-256 failed: ${ex.message}"))
                }
                return@launch
            }
            // Optimistically show the card; FwUploadStarted confirms total.
            _state.update {
                it.copy(
                    fwUpload = ch.ywesee.movementlogger.ui.FwUploadState(name, 0, image.size.toLong()),
                    fwUploadResult = null,
                )
            }
            log("FW upload $name (${image.size} B, sha256=${sha.joinToString("") { b -> "%02x".format(b) }.take(16)}…)")
            ble?.send(BleCmd.UploadFirmware(image, sha))
        }
    }

    /** Cancel an in-flight firmware upload (best-effort FW_ABORT). */
    fun abortFirmwareUpload() {
        log("FW upload cancel")
        ble?.send(BleCmd.AbortFirmware)
    }

    fun dismissFwUploadResult() {
        _state.update { it.copy(fwUploadResult = null) }
    }

    /** SAF display name for a content Uri, or null. */
    private fun displayName(ctx: Context, uri: android.net.Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) {
        uri.lastPathSegment
    }

    fun clearSession() {
        if (_state.value.sessionRunning != null) {
            log("LOG session duration reached — box is idle again (manual mode)")
            _state.update { it.copy(sessionRunning = null) }
        }
    }

    // ---------------- Event handling --------------------------------------

    private fun onEvent(e: BleEvent) {
        when (e) {
            is BleEvent.Status -> log(e.msg)
            is BleEvent.Error -> {
                log("ERROR: ${e.msg}")
                // Any op that ended in an error releases the brief-op guard
                // (DELETE / mode-query aborts all surface here).
                briefOpInFlight = false
                // Surface DELETE rejections prominently — the box refuses
                // some Debug rows (8.3-name miss -> NOT_FOUND, >15 chars
                // -> BAD_REQUEST, logging active -> BUSY). Without this it
                // only hits the log and looks like the tap did nothing (#7).
                if (e.msg.startsWith("DELETE ")) {
                    _state.update { it.copy(deleteError = e.msg) }
                }
                // "another op is in flight" means the BLE worker rejected
                // the command BEFORE dispatching — the in-flight op (a
                // keep-synced READ or LIST) is still going. Clear the
                // optimistic UI flags but DON'T treat this as a real
                // sync abort below: the in-flight op will complete
                // normally and the next keep-synced tick re-evaluates.
                val isCollision = e.msg.startsWith("another op is in flight")
                if (isCollision) {
                    _state.update { it.copy(listing = false) }
                    // If a sync was JUST starting (queue not built yet,
                    // no in-flight READ) and its LIST got rejected,
                    // reset so the next tick retries. Common path on
                    // Connected: GetLogMode races with startSyncPass.
                    val st = _state.value
                    if (st.syncing && syncQueue.isEmpty() && st.syncInFlight == null) {
                        syncPending = false
                        _state.update {
                            it.copy(
                                syncing = false,
                                syncStatus = "Sync: deferred (box busy) — retrying",
                            )
                        }
                    }
                }
                // A *real* BLE error mid-sync would otherwise strand the
                // queue (syncInFlight never clears). Abort cleanly so the
                // next "Sync now" starts fresh; the size key means a
                // partial file is just re-pulled next time.
                if (!isCollision && _state.value.syncing) {
                    syncPending = false
                    syncQueue.clear()
                    // A resumable interruption (ReadAborted already
                    // fired) keeps its own resume message + banner —
                    // don't stomp it with "try again".
                    val resumable = _state.value.transferInterrupted
                    _state.update {
                        it.copy(
                            syncing = false,
                            syncInFlight = null,
                            syncPassTotal = 0,
                            syncQueueRemaining = 0,
                            syncPassTotalBytes = 0,
                            syncPassCompletedBytes = 0,
                            syncStatus = if (resumable) it.syncStatus
                                else "Sync aborted (BLE error) — try again",
                        )
                    }
                }
                // A brief op (DELETE / mode) may have just freed the worker
                // — let any queued manual download proceed.
                pumpManualQueue()
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
                // Ask the box which log-mode it's in so the UI toggle
                // reflects reality. Legacy PumpTsueri ignores 0x07 (no
                // reply) — the toggle just stays at its last/unknown state.
                briefOpInFlight = true
                ble?.send(BleCmd.GetLogMode)
                // Resume after an interrupted transfer (desktop v0.0.9):
                // the aborted partial is already in the mirror, so a
                // fresh sync pass skips every complete file and re-pulls
                // only the unfinished one from its mirror offset. Also
                // resume if "Keep synced" is on. A plain first connect
                // does neither.
                val st = _state.value
                val resumeWhy: String? = when {
                    st.transferInterrupted -> "Resume"
                    st.keepSynced -> "Keep synced"
                    else -> null
                }
                if (st.transferInterrupted) {
                    _state.update { it.copy(transferInterrupted = false) }
                }
                // Stamp the box's open Sens/Gps CSVs with the phone's wall
                // clock (SET_TIME 0x08) on EVERY connect — "first time and
                // every time the box connects". The box has no RTC; it pairs
                // this epoch with its free-running ms counter (the CSV `ms`
                // column) and writes a `# SYNC` anchor, so Replay can
                // time-align without a GPS fix.
                //
                // GetLogMode → SET_TIME → startSyncPass are serialised with
                // 500 ms gaps: the firmware holds only ONE pending command at
                // a time (a second write clobbers the first) and the BLE
                // worker is single-op. The epoch is sampled right before the
                // send so it matches the box-tick the firmware stamps (skew =
                // one BLE interval, not the ~500 ms it would be if sampled at
                // connect time). The first 500 ms also lets the in-flight
                // GetLogMode `ModeReq` finish so SET_TIME isn't rejected as
                // "another op is in flight". (iOS v0.0.x equivalent.)
                scope.launch {
                    kotlinx.coroutines.delay(500)
                    if (_state.value.connection != Connection.Connected) return@launch
                    ble?.send(BleCmd.SetTime(System.currentTimeMillis()))
                    val why = resumeWhy ?: return@launch
                    // Another 500 ms so the LIST inside startSyncPass doesn't
                    // clobber the SET_TIME write.
                    kotlinx.coroutines.delay(500)
                    val now = _state.value
                    if (now.connection == Connection.Connected && !now.syncing) {
                        startSyncPass(why)
                    }
                }
            }
            BleEvent.Disconnected -> {
                // Drop the live-stream buffers too — the box may resume
                // with a fresh timestamp axis on reconnect, and a stale
                // sparkline would falsely suggest the stream is still alive.
                connectedBoxId = null
                syncPending = false
                briefOpInFlight = false
                syncQueue.clear()
                manualQueue.clear()
                // Keep the "Keep synced" toggle + poll job alive across a
                // disconnect — its tick guards on Connected, so it simply
                // resumes after a reconnect (sets up Stage 3 auto-resume).
                _state.update {
                    it.copy(
                        connection = Connection.Disconnected,
                        files = emptyList(),
                        listing = false,
                        syncing = false,
                        syncInFlight = null,
                        syncPassTotal = 0,
                        syncQueueRemaining = 0,
                        syncPassTotalBytes = 0,
                        syncPassCompletedBytes = 0,
                        downloads = emptyMap(),
                        queuedDownloads = emptyList(),
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
                _state.update { s ->
                    s.copy(
                        listing = false,
                        // Refresh per-file mirror sizes so the UI knows
                        // which files are already fully downloaded.
                        localBytes = s.files.associate { it.name to localSizeOf(it.name) },
                    )
                }
                log("LIST done (${_state.value.files.size} files)")
                if (syncPending) {
                    syncPending = false
                    runSyncDiff()
                }
                // Drain any downloads the user queued while the LIST ran.
                pumpManualQueue()
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
                        localBytes = s.localBytes + (e.name to localSize),
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
                    // Fold this file's final size into the cumulative-
                    // byte counter so the overall progress bar snaps
                    // forward as each file completes, then advance.
                    _state.update {
                        it.copy(
                            syncInFlight = null,
                            syncPassCompletedBytes = it.syncPassCompletedBytes + localSize,
                        )
                    }
                    pumpSyncQueue()
                }
                // Worker just freed up — start the next queued manual
                // download (no-op if the sync pass above re-armed a READ,
                // since the idle guard then fails).
                pumpManualQueue()
            }
            is BleEvent.ReadAborted -> {
                // Link dropped / stalled mid-file. Persist the partial
                // into the mirror so the resume continues from the
                // *true* break point (desktop v0.0.9). NOT markSynced —
                // incomplete; the next pass re-pulls only the remaining
                // tail via mirrorOffset. The Error that follows clears
                // the queue.
                val (_, have) = appendMirror(e.name, e.base, e.content)
                syncInFlight = null
                _state.update {
                    it.copy(
                        downloads = it.downloads - e.name,
                        transferInterrupted = true,
                        syncStatus = "Transfer interrupted — reconnect to resume " +
                            "($have B of ${e.name} kept)",
                    )
                }
                log("kept $have B of ${e.name} for resume")
            }
            is BleEvent.DeleteDone -> {
                briefOpInFlight = false
                _state.update { s ->
                    s.copy(files = s.files.filterNot { it.name == e.name },
                           deleteError = null)
                }
                log("deleted ${e.name}")
                pumpManualQueue()
            }
            is BleEvent.LogMode -> {
                briefOpInFlight = false
                _state.update { it.copy(logModeManual = e.manual) }
                // Mirror desktop's `autostart::sync_with_mode(manual)`:
                // AUTO arms the schedule, MANUAL cancels it.
                appContext?.let { ctx ->
                    AgentConfig.setLogModeManual(ctx, e.manual)
                    BackgroundSync.refresh(ctx)
                }
                log("box log mode: ${if (e.manual) "manual" else "auto"}")
                pumpManualQueue()
            }
            is BleEvent.Sample -> onSample(e.sample)
            is BleEvent.FwUploadStarted -> {
                _state.update { s ->
                    val cur = s.fwUpload
                    s.copy(
                        fwUpload = (cur ?: ch.ywesee.movementlogger.ui.FwUploadState(
                            "firmware.bin", 0, e.total)).copy(bytesDone = 0, total = e.total),
                    )
                }
                log("FW upload started (${e.total} B)")
            }
            is BleEvent.FwUploadProgress -> _state.update { s ->
                val cur = s.fwUpload ?: return@update s
                s.copy(fwUpload = cur.copy(bytesDone = e.bytesDone, total = e.total))
            }
            is BleEvent.FwUploadDone -> {
                _state.update {
                    it.copy(
                        fwUpload = null,
                        fwUploadResult = ch.ywesee.movementlogger.ui.FwUploadResult(
                            e.success, e.message),
                    )
                }
                log("FW upload ${if (e.success) "OK" else "FAILED"}: ${e.message}")
            }
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

    /** Local mirror size in bytes (0 if not downloaded yet). */
    private fun localSizeOf(name: String): Long =
        mirrorFile(name).let { if (it.exists()) it.length() else 0L }

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
        // Keep the public Downloads mirror in lockstep: a re-pull starts
        // from offset 0, so the old bytes there are stale too.
        publicMirror?.delete(name)
        return 0L
    }

    /**
     * Append a streamed segment at `base` (creating the file). If the
     * file length doesn't match `base` the resume is misaligned —
     * realign to `base` so we never interleave a corrupt prefix.
     * Returns (absolutePath, new local size).
     *
     * On success the same chunk is mirrored into the public Downloads
     * folder via [PublicMirror] so Google Files / any external file
     * manager can see and share it. That side-effect is best-effort:
     * it can't fail in a way that breaks the canonical local write.
     */
    private fun appendMirror(name: String, base: Long, bytes: ByteArray): Pair<String, Long> {
        return try {
            val f = mirrorFile(name)
            f.parentFile?.mkdirs()
            java.io.RandomAccessFile(f, "rw").use { raf ->
                if (raf.length() != base) raf.setLength(base.coerceAtMost(raf.length()))
                raf.seek(raf.length())
                raf.write(bytes)
                publicMirror?.appendAt(name, base, bytes)
                Pair(f.absolutePath, raf.length())
            }
        } catch (ex: Exception) {
            log("ERROR: mirror $name: ${ex.message}")
            Pair("", base + bytes.size)
        }
    }

    private fun log(msg: String) {
        val now = Date()
        val stamp = tsFmt.format(now)
        _state.update { it.copy(log = (it.log + "$stamp  $msg").takeLast(MAX_LOG_LINES)) }
        listener?.onStateChanged(_state.value)
        appendLogFile(fileTsFmt.format(now), msg)
    }

    /**
     * Persist every log line to `<externalFilesDir>/movement_logger.log`
     * (same folder as the downloaded recordings, so it survives app
     * restarts and is reachable via the device's file manager / adb).
     * Append-only with a soft size cap: once the file passes
     * [MAX_LOG_FILE_BYTES] it's rotated to `.1` so it can't grow forever.
     */
    @Synchronized
    private fun appendLogFile(stamp: String, msg: String) {
        val f = logFile() ?: return
        try {
            if (f.length() > MAX_LOG_FILE_BYTES) {
                val bak = File(f.parentFile, f.name + ".1")
                bak.delete()
                f.renameTo(bak)
            }
            f.parentFile?.mkdirs()
            java.io.FileOutputStream(f, true).bufferedWriter().use {
                it.append(stamp).append("  ").append(msg).append('\n')
            }
        } catch (_: Exception) {
            // Logging must never crash the app — a full disk / revoked
            // storage just means no on-disk copy this run.
        }
    }

    private fun logFile(): File? {
        val dir = appContext?.getExternalFilesDir(null) ?: appContext?.filesDir ?: return null
        return File(dir, "movement_logger.log")
    }

    /** Absolute path of the on-disk log, or null before [ensureInit]. */
    fun logFilePath(): String? = logFile()?.absolutePath

    /** True while BLE work is active — used by [BleSyncService] to decide
     *  whether to stay foreground or stop itself. */
    fun isBusy(): Boolean {
        val s = _state.value
        return s.connection != Connection.Disconnected ||
            s.scanning ||
            s.listing ||
            s.syncing ||
            s.downloads.isNotEmpty() ||
            s.fwUpload != null
    }

    /** "Keep synced" idle poll interval (desktop SYNC_POLL_INTERVAL). */
    private const val SYNC_POLL_INTERVAL_MS = 30_000L
    private const val MAX_LOG_LINES = 200
    /** Rotate the on-disk log past this size (1 MiB ≈ tens of sessions). */
    private const val MAX_LOG_FILE_BYTES = 1L * 1024 * 1024
    /** Bounded rolling buffer for the Live tab sparklines. 120 × 2 s = 4 min. */
    private const val LIVE_HISTORY_LEN = 120
}

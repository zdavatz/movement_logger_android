package ch.ywesee.movementlogger.ble

import android.content.Context
import ch.ywesee.movementlogger.data.PublicMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * u-blox UBX survey over the BLE box-bridge — the engine behind the "GPS Debug"
 * screen. Kotlin port of the desktop `gps-debug` survey
 * (movement_logger_desktop `gps-debug/src/survey.rs`).
 *
 * The receiver is bridged over BLE: the box relays raw UBX frames both ways
 * (firmware opcodes 0x0D GPS_BRIDGE / 0x0E GPS_TX, v0.0.17+). Once a second we
 * send zero-length UBX poll requests (NAV-PVT / NAV-DOP / NAV-SAT / NAV-SIG /
 * MON-RF); the bridged replies arrive as FileData notifies, are reassembled by
 * [UbxParser], and written to two CSVs matching the desktop schema exactly.
 *
 * Non-destructive: we only ever *poll* (empty-payload requests). Owned/driven
 * by [FileSyncCore], which wires [sendBridge]/[sendPoll] to its single
 * [BleClient] and forwards every [BleEvent.UbxFrame] into [feed].
 */
object BleGpsSurvey {

    data class UiState(
        val running: Boolean = false,
        val label: String = "antenna",
        val epochCount: Int = 0,
        val log: List<String> = emptyList(),
        val epochCsvPath: String? = null,
        val signalsCsvPath: String? = null,
        val spectrumCsvPath: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Notified when [running] flips so [FileSyncCore] can gate the sync loops. */
    var onRunningChanged: ((Boolean) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val lock = Any()   // guards [parser] + [cur] across feed/flush threads

    private var sendBridge: ((Boolean) -> Unit)? = null
    private var sendPoll: ((ByteArray) -> Unit)? = null

    private var parser = UbxParser()
    private var cur = Epoch()
    private var label = "antenna"
    private var epochCount = 0
    private val logLines = ArrayList<String>()

    private var epochFile: File? = null
    private var sigFile: File? = null
    private var epochOut: FileOutputStream? = null
    private var sigOut: FileOutputStream? = null
    // Spectrum CSV is created lazily on the first MON-SPAN reply — M8
    // receivers never answer the poll, and an empty spectrum CSV would read
    // as "no interference" (desktop parity).
    private var specFile: File? = null
    private var specOut: FileOutputStream? = null
    private var publicMirror: PublicMirror? = null
    private var epochPubOffset = 0L
    private var sigPubOffset = 0L
    private var specPubOffset = 0L

    private const val MAX_LOG_LINES = 200
    private const val EPOCH_HEADER =
        "label,host_iso,iTOW_ms,utc_date,utc_time,timeValid,fixType,gnssFixOK,numSV_used," +
            "lat_deg,lon_deg,height_m,hMSL_m,hAcc_m,vAcc_m,sAcc_mps,pDOP,hDOP,vDOP," +
            "antStatus,antPower,noisePerMS,agcCnt,cwSuppression_jamInd,jammingState\n"
    private const val SIG_HEADER =
        "label,host_iso,iTOW_ms,gnssId,gnss,svId,sigId,sig,cno_dbhz,elev_deg,azim_deg," +
            "qualityInd,svUsed,prUsed,prRes_m\n"
    private const val SPEC_HEADER =
        "label,host_iso,iTOW_ms,block,center_hz,span_hz,res_hz,pga_db," +
            "peak_bin,peak_amp,peak_freq_hz,bins\n"

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val isRunning: Boolean get() = _state.value.running

    fun setLabel(value: String) {
        if (isRunning) return
        label = value
        _state.value = _state.value.copy(label = value)
    }

    // ---- control -----------------------------------------------------------

    /**
     * Start a survey: open the two CSVs, turn the bridge on, and begin the 1 Hz
     * poll loop. Caller ([FileSyncCore]) has already verified the link is
     * connected and idle.
     */
    fun start(context: Context, label: String, sendBridge: (Boolean) -> Unit, sendPoll: (ByteArray) -> Unit) {
        if (isRunning) return
        this.sendBridge = sendBridge
        this.sendPoll = sendPoll
        this.label = sanitize(label)
        epochCount = 0
        logLines.clear()
        synchronized(lock) { parser = UbxParser(); cur = Epoch() }
        openCsvs(context)
        appendLog("BLE GPS survey — polling the u-blox over the box · label ${this.label}")
        epochFile?.let { appendLog("epoch  -> ${it.absolutePath}") }
        sigFile?.let { appendLog("signals-> ${it.absolutePath}") }
        publishState(running = true)
        onRunningChanged?.invoke(true)
        sendBridge(true)
        sendPolls()                       // prime the first collection window
        pollJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                flushEpoch()              // write whatever landed this window
                synchronized(lock) { cur = Epoch() }
                sendPolls()
            }
        }
    }

    /** Stop the survey: turn the bridge off, flush the last epoch, close files. */
    fun stop() {
        if (!isRunning) return
        pollJob?.cancel(); pollJob = null
        sendBridge?.invoke(false)
        flushEpoch()
        appendLog("stopped after $epochCount epoch(s).")
        runCatching { epochOut?.close() }; epochOut = null
        runCatching { sigOut?.close() }; sigOut = null
        runCatching { specOut?.close() }; specOut = null
        publishState(running = false)
        onRunningChanged?.invoke(false)
    }

    /** Feed raw bridged UBX bytes (one FileData notify) into the parser. */
    fun feed(data: ByteArray) {
        if (!isRunning) return
        synchronized(lock) {
            val frames = ArrayList<UbxFrame>()
            parser.feed(data, frames)
            for (fr in frames) {
                when (fr.cls to fr.id) {
                    NAV_PVT -> cur.pvt = parseNavPvt(fr.payload)
                    NAV_DOP -> cur.dop = parseNavDop(fr.payload)
                    NAV_SAT -> cur.sats = parseNavSat(fr.payload)
                    NAV_SIG -> cur.sigs = parseNavSig(fr.payload)
                    MON_RF -> cur.rf = parseMonRf(fr.payload)
                    MON_SPAN -> cur.span = parseMonSpan(fr.payload)
                }
            }
        }
    }

    // ---- internals ---------------------------------------------------------

    private fun sendPolls() {
        for (m in POLLS) sendPoll?.invoke(pollFrame(m))
    }

    private fun flushEpoch() {
        val host = isoFmt.format(Date())
        val snapshot: Epoch
        synchronized(lock) { snapshot = cur.copy() }
        writeEpochRows(host, snapshot)
        writeSpectrumRows(host, snapshot)
        appendLog(liveSummary(snapshot))
        epochCount += 1
        publishState(running = isRunning)
    }

    private fun writeEpochRows(host: String, ep: Epoch) {
        val itow = ep.pvt?.itow ?: 0L
        ep.pvt?.let { p ->
            val dop = ep.dop ?: NavDop()
            val rf = ep.rf ?: MonRf()
            val date = "%04d-%02d-%02d".format(p.year, p.month, p.day)
            val time = "%02d:%02d:%02d".format(p.hour, p.min, p.sec)
            val row = "$label,$host,$itow,$date,$time,0x%02X,".format(p.valid) +
                "${p.fixType},${if (p.gnssFixOk) 1 else 0},${p.numSv}," +
                "%.7f,%.7f,%.3f,%.3f,%.3f,%.3f,%.3f,".format(
                    p.latDeg, p.lonDeg, p.heightM, p.hmslM, p.haccM, p.vaccM, p.saccMps
                ) +
                "%.2f,%.2f,%.2f,".format(p.pdop, dop.hdop, dop.vdop) +
                "${antStatusName(rf.antStatus)},${antPowerName(rf.antPower)}," +
                "${rf.noisePerMs},${rf.agcCnt},${rf.jamInd},${jammingName(rf.jammingState)}\n"
            writeRow(row, epoch = true)
        }

        // Per-signal rows: NAV-SIG is the per-signal truth; elev/azim/svUsed come
        // from the matching NAV-SAT satellite. Fall back to per-satellite rows if
        // the receiver answered NAV-SAT but not NAV-SIG.
        if (ep.sigs.isNotEmpty()) {
            for (s in ep.sigs) {
                val sat = ep.sats.firstOrNull { it.gnss == s.gnss && it.sv == s.sv }
                val elev = sat?.elev?.toString() ?: ""
                val azim = sat?.azim?.toString() ?: ""
                val svUsed = if (sat?.svUsed == true) 1 else 0
                val row = "$label,$host,$itow,${s.gnss},${gnssName(s.gnss)},${s.sv},${s.sig}," +
                    "${sigName(s.gnss, s.sig)},${s.cno},$elev,$azim,${s.qual},$svUsed," +
                    "${if (s.prUsed) 1 else 0},%.1f\n".format(s.prResM)
                writeRow(row, epoch = false)
            }
        } else {
            for (a in ep.sats) {
                val row = "$label,$host,$itow,${a.gnss},${gnssName(a.gnss)},${a.sv},,," +
                    "${a.cno},${a.elev},${a.azim},${a.qual},${if (a.svUsed) 1 else 0},," +
                    "%.1f\n".format(a.prResM)
                writeRow(row, epoch = false)
            }
        }
    }

    private fun writeRow(row: String, epoch: Boolean) {
        val bytes = row.toByteArray(Charsets.UTF_8)
        val out = if (epoch) epochOut else sigOut
        val name = (if (epoch) epochFile else sigFile)?.name
        runCatching { out?.write(bytes); out?.flush() }
        // Best-effort public mirror so the CSVs show in Download/MovementLogger.
        if (name != null) {
            val base = if (epoch) epochPubOffset else sigPubOffset
            runCatching { publicMirror?.appendAt(name, base, bytes) }
            if (epoch) epochPubOffset += bytes.size else sigPubOffset += bytes.size
        }
    }

    /**
     * Spectrum rows: one per RF block per epoch. `bins` is the raw 256-value
     * amplitude vector, space-separated inside one CSV field; peak_* pre-computes
     * the strongest bin. The CSV (and its log line) appear on the first MON-SPAN
     * reply only.
     */
    private fun writeSpectrumRows(host: String, ep: Epoch) {
        if (ep.span.isEmpty()) return
        if (specOut == null) {
            val sf = specFile ?: return
            runCatching { publicMirror?.delete(sf.name) }
            specPubOffset = 0L
            specOut = runCatching {
                FileOutputStream(sf, false).also { it.write(SPEC_HEADER.toByteArray()); it.flush() }
            }.getOrNull() ?: return
            runCatching { publicMirror?.appendAt(sf.name, 0L, SPEC_HEADER.toByteArray()) }
            specPubOffset = SPEC_HEADER.toByteArray().size.toLong()
            appendLog("spectrum-> ${sf.absolutePath}")
        }
        val itow = ep.pvt?.itow ?: 0L
        for ((bi, b) in ep.span.withIndex()) {
            val (pi, pa) = b.peak()
            val bins = b.spectrum.joinToString(" ")
            val row = "$label,$host,$itow,$bi,${b.centerHz},${b.spanHz},${b.resHz},${b.pgaDb}," +
                "$pi,$pa,%.0f,$bins\n".format(b.binFreqHz(pi))
            val bytes = row.toByteArray(Charsets.UTF_8)
            runCatching { specOut?.write(bytes); specOut?.flush() }
            specFile?.name?.let { name ->
                runCatching { publicMirror?.appendAt(name, specPubOffset, bytes) }
                specPubOffset += bytes.size
            }
        }
    }

    /**
     * Peter's EMI assembly metrics (2026-07-17): `avg6`/`min6`/`max6` are the
     * mean/weakest/strongest of the 6 strongest **GPS + Galileo** satellites
     * (other constellations excluded), plus `used` (sats in the nav
     * solution), `jam` (narrowband jamming), `noise` (broadband noise floor)
     * and `agc` (antenna-signal gain). All collapse within a second of
     * closing the case / powering a noisy subsystem, long before the fix
     * itself reacts. Matches the desktop live line.
     */
    private fun liveSummary(ep: Epoch): String {
        val p = ep.pvt
            ?: return "(no NAV-PVT reply — receiver may be NMEA-only, or the box firmware lacks the GPS bridge)"
        val top6 = gpsGalSatCnos(ep.sats, ep.sigs).take(6)
        val avg6 = if (top6.isEmpty()) 0.0 else top6.sum().toDouble() / top6.size
        val max6 = top6.firstOrNull() ?: 0
        val min6 = top6.lastOrNull() ?: 0
        val used = maxOf(ep.sigs.count { it.prUsed }, ep.sats.count { it.svUsed })
        val rf = ep.rf
        val rfs = if (rf != null) {
            "ant=%s/%s jam=%d(%s) noise=%d agc=%d".format(
                antStatusName(rf.antStatus), antPowerName(rf.antPower),
                rf.jamInd, jammingName(rf.jammingState), rf.noisePerMs, rf.agcCnt)
        } else {
            "ant=?/? jam=? noise=? agc=?"
        }
        val spanS = ep.span.firstOrNull()?.let { b ->
            val (pi, pa) = b.peak()
            " | peak %.1fMHz a=%d".format(b.binFreqHz(pi) / 1e6, pa)
        } ?: ""
        return "%02d:%02d:%02d fix=%s ok=%d sv=%2d used=%2d avg6=%4.1f min6=%2d max6=%2d hAcc=%.1fm pDOP=%.1f | %s%s"
            .format(p.hour, p.min, p.sec, fixTypeName(p.fixType), if (p.gnssFixOk) 1 else 0,
                p.numSv, used, avg6, min6, max6, p.haccM, p.pdop, rfs, spanS)
    }

    // ---- CSV files ---------------------------------------------------------

    private fun openCsvs(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "gps-debug")
        dir.mkdirs()
        val safe = label
        val ef = File(dir, "${safe}_gnss_epoch.csv")
        val sf = File(dir, "${safe}_gnss_signals.csv")
        epochFile = ef; sigFile = sf
        // Spectrum CSV path is fixed now, but the file is only created on the
        // first MON-SPAN reply (writeSpectrumRows). Drop any stale copy from a
        // previous run so it can't be mistaken for this run's data.
        val xf = File(dir, "${safe}_gnss_spectrum.csv")
        specFile = xf; specOut = null; specPubOffset = 0L
        runCatching { xf.delete() }
        publicMirror = PublicMirror(context.applicationContext)
        // Fresh files each run (truncate), matching the desktop's File::create.
        runCatching { publicMirror?.delete(ef.name) }
        runCatching { publicMirror?.delete(sf.name) }
        runCatching { publicMirror?.delete(xf.name) }
        epochPubOffset = 0L; sigPubOffset = 0L
        epochOut = FileOutputStream(ef, false).also { it.write(EPOCH_HEADER.toByteArray()); it.flush() }
        sigOut = FileOutputStream(sf, false).also { it.write(SIG_HEADER.toByteArray()); it.flush() }
        runCatching { publicMirror?.appendAt(ef.name, 0L, EPOCH_HEADER.toByteArray()) }
        runCatching { publicMirror?.appendAt(sf.name, 0L, SIG_HEADER.toByteArray()) }
        epochPubOffset = EPOCH_HEADER.toByteArray().size.toLong()
        sigPubOffset = SIG_HEADER.toByteArray().size.toLong()
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
    }

    private fun publishState(running: Boolean) {
        _state.value = UiState(
            running = running,
            label = label,
            epochCount = epochCount,
            log = ArrayList(logLines),
            epochCsvPath = epochFile?.absolutePath,
            signalsCsvPath = sigFile?.absolutePath,
            // Only surfaced once the receiver actually answered MON-SPAN.
            spectrumCsvPath = if (specOut != null) specFile?.absolutePath else null,
        )
    }

    private fun sanitize(s: String): String {
        val cleaned = s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return cleaned.ifEmpty { "antenna" }
    }
}

package ch.ywesee.movementlogger.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import ch.ywesee.movementlogger.data.CsvParsers
import ch.ywesee.movementlogger.data.GpsMath
import ch.ywesee.movementlogger.data.PublicMirror
import ch.ywesee.movementlogger.data.RideMapRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI-facing snapshot of the u-blox GPS reader. Mirrors the pattern of
 * [ch.ywesee.movementlogger.ui.FileSyncUiState]: a single immutable
 * data class exposed via [StateFlow], updated atomically by the core.
 */
data class UbloxUiState(
    /** Was a matching u-blox device enumerated by [UbloxGpsCore.refreshDevice]? */
    val deviceFound: Boolean = false,
    val deviceName: String? = null,
    val hasPermission: Boolean = false,
    val isReading: Boolean = false,
    val status: String = "Plug in the u-blox GNSS receiver",

    // --- Most-recent fix ---
    val utc: String? = null,            // hhmmss.ss as broadcast by the receiver
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val altM: Double? = null,
    val speedKmh: Double? = null,
    val courseDeg: Double? = null,
    val fixQuality: Int = 0,            // 0 = no fix
    val numSat: Int = 0,
    val hdop: Double? = null,

    // --- Rate counters ---
    /** Decaying running average of RMC arrivals/sec over the last [WINDOW] sentences. */
    val rmcHz: Double = 0.0,
    /** Same for GGA. The two are usually equal but split if one is disabled. */
    val ggaHz: Double = 0.0,
    /** Total sentences (RMC + GGA) parsed since the reader started. */
    val sentenceCount: Long = 0L,

    // --- CSV logger ---
    val isLogging: Boolean = false,
    val logPath: String? = null,
    val loggedRows: Long = 0L,
)

/**
 * One finished (or in-progress) `UbloxGps_*.csv` recording plus the
 * summary stats shown in the GPS tab's overview list. Computed by
 * [UbloxGpsCore.refreshRecordings] off the main thread.
 */
data class GpsRecording(
    val name: String,
    val path: String,
    /** Peak `SpeedKMh` column value across the file. */
    val maxSpeedKmh: Double,
    /** Sum of haversine hops between consecutive fixed points, metres. */
    val distanceMeters: Double,
    /** Wall-clock span of the log from the `Time [10ms]` tick column, seconds. */
    val durationSec: Long,
    val rows: Int,
    /** True for the file currently being written (no swipe-delete, live-ish stats). */
    val isRecording: Boolean,
)

/**
 * Process-wide singleton: BLE has [ch.ywesee.movementlogger.ble.FileSyncCore]
 * for state survival across Activity recreation; we do the same here so
 * unplug-replug + screen-rotation don't drop the running CSV log.
 *
 * Owns:
 *  - a [UbloxGpsReader] (created on connect, torn down on disconnect)
 *  - a [BufferedWriter] for the active CSV file (when logging)
 *  - a [StateFlow] consumed by the GPS tab Compose UI
 *
 * Threading: the reader callbacks land on its IO worker; we use atomic
 * `_state.update { ... }` to publish without locks. CSV writes happen
 * inline on the reader thread — well under 1 ms per row at expected
 * rates (≤25 Hz), so no dedicated dispatcher needed.
 */
@SuppressLint("StaticFieldLeak")
object UbloxGpsCore : UbloxGpsReader.Sink {

    private const val TAG = "UbloxGpsCore"
    private const val ACTION_USB_PERMISSION = "ch.ywesee.movementlogger.USB_PERMISSION"
    private const val UBLOX_VID = 0x1546
    private const val UBLOX_PID = 0x01a8

    /** Rate-counter window: we average per-sentence intervals over the
     *  last N arrivals so the displayed Hz is responsive (≤5 s lag at
     *  1 Hz) but not jittery. */
    private const val WINDOW = 10

    /** Distance accumulation drops any single inter-fix hop longer than
     *  this (metres) as a GPS glitch — see [computeStats]. */
    private const val MAX_HOP_M = 60.0

    /** Belt-and-braces gates for the LIVE max-speed fold (the primary
     *  defence is upstream: [maybeFlushCsvRow] never logs speed from a void
     *  RMC). Finished-file stats use the stronger position-consistency rule
     *  in [RideMapRenderer.robustTopSpeed] instead. */
    private const val FIX_NEIGHBOR_TICKS = 50.0
    /** Same hard clip as `GpsMath.smoothSpeedKmh`. */
    private const val MAX_PLAUSIBLE_SPEED_KMH = 60.0

    private val _state = MutableStateFlow(UbloxUiState())
    val state: StateFlow<UbloxUiState> = _state.asStateFlow()

    /** Saved recordings (newest first) with their overview stats. Rebuilt
     *  by [refreshRecordings] on init, on start/stop, and on a UI tick. */
    private val _recordings = MutableStateFlow<List<GpsRecording>>(emptyList())
    val recordings: StateFlow<List<GpsRecording>> = _recordings.asStateFlow()

    /** Off-main scope for file scans + stat computation. Reader callbacks
     *  and CSV writes stay on the USB reader thread; this is only for the
     *  recordings list so a directory scan can't jank the UI. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var usbManager: UsbManager? = null
    private var reader: UbloxGpsReader? = null
    private var permissionReceiver: BroadcastReceiver? = null

    /** Pending RMC fields waiting for the next GGA (or vice-versa) so we
     *  can write a single combined CSV row per second. */
    private var pendingRmc: RmcFix? = null
    private var pendingGga: GgaFix? = null

    private val rmcTimes = ArrayDeque<Long>()
    private val ggaTimes = ArrayDeque<Long>()

    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var csvStartMonoMs: Long = 0L
    /** Lazily created on first [init]; mirrors every CSV byte into
     *  `Download/MovementLogger/` so Google Files / file managers can see
     *  the GPS log without poking at app-private storage. */
    private var publicMirror: PublicMirror? = null
    /** Running byte count of the active CSV so [PublicMirror.appendAt]
     *  gets the correct resume offset (header + every row written so far). */
    private var csvPublicOffset: Long = 0L

    /** Live stats for the in-flight recording, folded per written row in
     *  [maybeFlushCsvRow] so the 3 s recordings-list tick never re-reads the
     *  growing file. Re-parsing it every tick is what exhausted native
     *  memory on the 11.7.2026 water test — see [fastDoubleOrNull]. */
    @Volatile private var liveStats = GpsStats.EMPTY
    private var liveFirstTick = Double.NaN
    private var livePrevLat = Double.NaN
    private var livePrevLon = Double.NaN
    private var liveLastFixTick = Double.NEGATIVE_INFINITY

    /** Finished recordings never change, so stats are parsed once per
     *  (name, size) and cached; entries for deleted files are pruned on the
     *  next [refreshRecordings] pass. */
    private val statsCache = HashMap<String, Pair<Long, GpsStats>>()

    private val dateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        reader = UbloxGpsReader(usbManager!!, this)
        publicMirror = PublicMirror(ctx.applicationContext)
        registerPermissionReceiver()
        refreshDevice()
        refreshRecordings()
    }

    /** Re-scan the USB device list and update [UbloxUiState.deviceFound]. */
    fun refreshDevice() {
        val mgr = usbManager ?: return
        val all = mgr.deviceList.values.toList()
        Log.i(TAG, "refreshDevice: ${all.size} USB device(s) enumerated")
        for (d in all) {
            Log.i(TAG, "  - vid=0x${"%04x".format(d.vendorId)} pid=0x${"%04x".format(d.productId)} name=${d.productName}")
        }
        val device = all.firstOrNull {
            it.vendorId == UBLOX_VID && it.productId == UBLOX_PID
        }
        _state.update {
            it.copy(
                deviceFound = device != null,
                deviceName = device?.productName,
                hasPermission = device?.let(mgr::hasPermission) ?: false,
                status = when {
                    device == null -> "Plug in the u-blox GNSS receiver"
                    !mgr.hasPermission(device) -> "USB permission required — tap Connect"
                    !_state.value.isReading -> "Found ${device.productName ?: "u-blox"} — tap Connect"
                    else -> it.status
                },
            )
        }
    }

    /**
     * Top-level "Connect" button handler. If the device is enumerated
     * but permission hasn't been granted, fires the system permission
     * dialog. If permission is already granted, starts the read loop.
     */
    fun connect() {
        val ctx = appContext ?: return
        val mgr = usbManager ?: return
        val device = mgr.deviceList.values.firstOrNull {
            it.vendorId == UBLOX_VID && it.productId == UBLOX_PID
        }
        Log.i(TAG, "connect: device=$device hasPermission=${device?.let(mgr::hasPermission)}")
        if (device == null) {
            _state.update { it.copy(status = "No u-blox device — re-plug and tap Refresh") }
            return
        }
        if (!mgr.hasPermission(device)) {
            requestPermission(ctx, mgr, device)
            return
        }
        startReader(device)
    }

    fun disconnect() {
        reader?.stop()
        _state.update {
            it.copy(
                isReading = false,
                status = "Disconnected",
                rmcHz = 0.0, ggaHz = 0.0,
            )
        }
        rmcTimes.clear()
        ggaTimes.clear()
        pendingRmc = null
        pendingGga = null
        stopLogging()
    }

    // --- CSV logger ---

    fun startLogging() {
        if (csvWriter != null) return
        val dir = appContext?.getExternalFilesDir(null) ?: appContext?.filesDir ?: return
        val name = "UbloxGps_" + dateFmt.format(Date()) + ".csv"
        val file = File(dir, name)
        try {
            val w = BufferedWriter(FileWriter(file, false))
            // Header mirrors the box's Gps*.csv schema where the columns
            // overlap, so the existing CsvParsers.parseGpsStream parses
            // it without changes. Ticks = ms-since-log-start / 10 (the
            // box uses ThreadX 10 ms ticks; we synthesise one).
            val header = "Time [10ms],UTC,Lat [deg],Lon [deg],Alt [m],SpeedKMh,Course [deg],Fix,NumSat,HDOP\n"
            w.write(header)
            w.flush()
            csvWriter = w
            csvFile = file
            csvStartMonoMs = System.nanoTime() / 1_000_000L
            csvPublicOffset = 0L
            liveStats = GpsStats.EMPTY
            liveFirstTick = Double.NaN
            livePrevLat = Double.NaN
            livePrevLon = Double.NaN
            liveLastFixTick = Double.NEGATIVE_INFINITY
            // Drop any prior session with this exact name (timestamped, so
            // collisions are vanishingly rare — but a re-start within the
            // same second would otherwise append onto stale bytes).
            publicMirror?.delete(name)
            val headerBytes = header.toByteArray(Charsets.UTF_8)
            publicMirror?.appendAt(name, csvPublicOffset, headerBytes)
            csvPublicOffset += headerBytes.size
            _state.update {
                it.copy(isLogging = true, logPath = file.absolutePath, loggedRows = 0L)
            }
            refreshRecordings()
        } catch (e: Exception) {
            _state.update { it.copy(status = "CSV log open failed: ${e.message}") }
        }
    }

    fun stopLogging() {
        val w = csvWriter ?: return
        try { w.flush(); w.close() } catch (_: Exception) {}
        csvWriter = null
        // The finished file's stats equal the live counters — seed the cache
        // so the recording never needs a full re-parse in this process.
        csvFile?.let { f ->
            synchronized(statsCache) { statsCache[f.name] = f.length() to liveStats }
        }
        _state.update { it.copy(isLogging = false) }
        refreshRecordings()
    }

    // --- Recordings list + overview stats ---

    /**
     * Re-scan the app-private external dir for `UbloxGps_*.csv`, compute
     * each file's overview stats (max speed / distance / duration) on the
     * IO scope, and publish sorted newest-first. The filename timestamp
     * (`yyyyMMdd_HHmmss`) is lexically chronological, so a descending name
     * sort == newest-first without parsing dates.
     */
    fun refreshRecordings() {
        val dir = appContext?.getExternalFilesDir(null) ?: appContext?.filesDir ?: return
        val activeName = if (_state.value.isLogging) csvFile?.name else null
        scope.launch {
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("UbloxGps_") && it.name.endsWith(".csv") }
                .orEmpty()
            val list = files.map { f ->
                val s = statsFor(f, isActive = f.name == activeName)
                GpsRecording(
                    name = f.name,
                    path = f.absolutePath,
                    maxSpeedKmh = s.maxSpeedKmh,
                    distanceMeters = s.distanceMeters,
                    durationSec = s.durationSec,
                    rows = s.rows,
                    isRecording = f.name == activeName,
                )
            }.sortedByDescending { it.name }
            val names = files.mapTo(HashSet()) { it.name }
            synchronized(statsCache) { statsCache.keys.retainAll(names) }
            _recordings.value = list
        }
    }

    /** Stats for one recording: the in-flight file reads the live counters
     *  (zero file IO), finished files parse once and hit the size-keyed
     *  cache on every later pass. */
    private fun statsFor(f: File, isActive: Boolean): GpsStats {
        if (isActive) return liveStats
        val len = f.length()
        synchronized(statsCache) { statsCache[f.name] }
            ?.let { (size, s) -> if (size == len) return s }
        val computed = runCatching { computeStats(f) }.getOrNull() ?: return GpsStats.EMPTY
        synchronized(statsCache) { statsCache[f.name] = len to computed }
        return computed
    }

    /**
     * Delete a saved recording: the canonical file under the app-private
     * dir *and* its `Download/MovementLogger/` public mirror. Refuses the
     * file that's actively being written so a swipe can't corrupt the
     * open writer.
     */
    fun deleteRecording(name: String) {
        if (_state.value.isLogging && name == csvFile?.name) return
        scope.launch {
            val dir = appContext?.getExternalFilesDir(null) ?: appContext?.filesDir
            runCatching { dir?.let { File(it, name).delete() } }
            runCatching { publicMirror?.delete(name) }
            refreshRecordings()
        }
    }

    private data class GpsStats(
        val maxSpeedKmh: Double,
        val distanceMeters: Double,
        val durationSec: Long,
        val rows: Int,
    ) {
        companion object { val EMPTY = GpsStats(0.0, 0.0, 0L, 0) }
    }

    /**
     * Single-pass stat computation over one `UbloxGps_*.csv`. Columns are
     * the fixed schema written by [startLogging]:
     * `Time [10ms],UTC,Lat,Lon,Alt,SpeedKMh,Course,Fix,NumSat,HDOP`.
     *
     * - **Max speed** = [RideMapRenderer.robustTopSpeed] — ≤ 60 km/h and
     *   position-consistent, so the fantasy speed bursts u-blox emits while
     *   reacquiring a fix can't become the headline stat (the share-PNG
     *   footer runs the same code, so the two always agree).
     * - **Distance** = Σ haversine between consecutive rows that both have
     *   a fix (>0) and valid lat/lon. Hops > [MAX_HOP_M] are dropped as GPS
     *   glitches (a real hop that large implies >200 km/h at 1 Hz); the
     *   trade-off is a slight undercount across fix-loss gaps, preferred
     *   over a glitch spiking the total.
     * - **Duration** = `(lastTick − firstTick) × 10 ms`, drift-free vs. the
     *   phone monotonic clock the tick column is stamped from.
     *
     * Parsing goes through [CsvParsers.parseGpsStream] (regex-free since the
     * 11.7.2026 OOM — see `fastDoubleOrNull`); the materialised row list is
     * fine memory-wise because stats are computed once per (name, size) and
     * cached, never on the 3 s tick.
     */
    private fun computeStats(file: File): GpsStats {
        val rows = file.inputStream().use { CsvParsers.parseGpsStream(it) }
        if (rows.isEmpty()) return GpsStats.EMPTY
        val maxSpeed = RideMapRenderer.robustTopSpeed(rows)
        var dist = 0.0
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        for (r in rows) {
            if (r.fix > 0 && r.lat.isFinite() && r.lon.isFinite()) {
                if (!prevLat.isNaN()) {
                    val hop = GpsMath.haversineM(prevLat, prevLon, r.lat, r.lon)
                    if (hop <= MAX_HOP_M) dist += hop
                }
                prevLat = r.lat
                prevLon = r.lon
            }
        }
        val durSec = ((rows.last().ticks - rows.first().ticks) * 10.0 / 1000.0).toLong()
        return GpsStats(maxSpeed, dist, durSec, rows.size)
    }

    /** Fold one just-written CSV row into [liveStats] — mirrors
     *  [computeStats]' distance ([MAX_HOP_M] gate) and duration rules; max
     *  speed uses the forward-only gates above (a live fold can't apply the
     *  full position-consistency check, and doesn't need to — void-RMC
     *  speeds never reach here since [maybeFlushCsvRow] drops them). */
    private fun updateLiveStats(ticks: Long, speedKmh: Double?, fix: Int, lat: Double?, lon: Double?) {
        val s = liveStats
        if (liveFirstTick.isNaN()) liveFirstTick = ticks.toDouble()
        if (fix > 0) liveLastFixTick = ticks.toDouble()
        var maxSpeed = s.maxSpeedKmh
        if (speedKmh != null && speedKmh.isFinite() &&
            speedKmh <= MAX_PLAUSIBLE_SPEED_KMH &&
            ticks - liveLastFixTick <= FIX_NEIGHBOR_TICKS &&
            speedKmh > maxSpeed
        ) maxSpeed = speedKmh
        var dist = s.distanceMeters
        if (fix > 0 && lat != null && lon != null) {
            if (!livePrevLat.isNaN()) {
                val hop = GpsMath.haversineM(livePrevLat, livePrevLon, lat, lon)
                if (hop <= MAX_HOP_M) dist += hop
            }
            livePrevLat = lat
            livePrevLon = lon
        }
        val durSec = ((ticks - liveFirstTick) * 10.0 / 1000.0).toLong()
        liveStats = GpsStats(maxSpeed, dist, durSec, s.rows + 1)
    }

    // --- UbloxGpsReader.Sink ---

    override fun onRmc(fix: RmcFix, monoNanos: Long) {
        recordTime(rmcTimes, monoNanos)
        val hz = avgHz(rmcTimes)
        _state.update {
            it.copy(
                utc = fix.utc,
                latDeg = fix.latDeg ?: it.latDeg,
                lonDeg = fix.lonDeg ?: it.lonDeg,
                // Void (status V) RMC speed/course is reacquisition fantasy —
                // hold the last valid reading instead of flashing 27 km/h.
                speedKmh = fix.takeIf { f -> f.statusValid }?.speedKmh ?: it.speedKmh,
                courseDeg = fix.takeIf { f -> f.statusValid }?.courseDeg ?: it.courseDeg,
                rmcHz = hz,
                sentenceCount = it.sentenceCount + 1,
            )
        }
        pendingRmc = fix
        maybeFlushCsvRow()
    }

    override fun onGga(fix: GgaFix, monoNanos: Long) {
        recordTime(ggaTimes, monoNanos)
        val hz = avgHz(ggaTimes)
        _state.update {
            it.copy(
                utc = fix.utc,
                latDeg = fix.latDeg ?: it.latDeg,
                lonDeg = fix.lonDeg ?: it.lonDeg,
                altM = fix.altM ?: it.altM,
                fixQuality = fix.fixQuality,
                numSat = fix.numSat,
                hdop = fix.hdop ?: it.hdop,
                ggaHz = hz,
                sentenceCount = it.sentenceCount + 1,
            )
        }
        pendingGga = fix
        maybeFlushCsvRow()
    }

    override fun onLog(message: String) {
        Log.i(TAG, "reader -> $message")
        _state.update { it.copy(status = message) }
    }

    // --- helpers ---

    private fun startReader(device: UsbDevice) {
        val r = reader ?: return
        if (r.start(device)) {
            _state.update {
                it.copy(
                    isReading = true,
                    hasPermission = true,
                    deviceFound = true,
                    deviceName = device.productName,
                )
            }
            // Foreground service so screen-off / app-switch can't freeze the
            // read loop mid-recording. Self-stops when reading + logging end.
            appContext?.let { UbloxGpsService.start(it) }
        }
    }

    private fun requestPermission(ctx: Context, mgr: UsbManager, device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, flags)
        _state.update { it.copy(status = "Requesting USB permission…") }
        mgr.requestPermission(device, pi)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerPermissionReceiver() {
        if (permissionReceiver != null) return
        val ctx = appContext ?: return
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB permission result: granted=$granted device=$device")
                if (granted && device != null) {
                    _state.update { it.copy(hasPermission = true) }
                    startReader(device)
                } else {
                    _state.update { it.copy(status = "USB permission denied") }
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(recv, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(recv, filter)
        }
        permissionReceiver = recv
    }

    private fun recordTime(buf: ArrayDeque<Long>, monoNs: Long) {
        buf.addLast(monoNs)
        while (buf.size > WINDOW) buf.removeFirst()
    }

    private fun avgHz(buf: ArrayDeque<Long>): Double {
        if (buf.size < 2) return 0.0
        val span = buf.last() - buf.first()
        if (span <= 0) return 0.0
        // (n - 1) intervals fit between n timestamps; rate = intervals per second.
        return (buf.size - 1).toDouble() * 1_000_000_000.0 / span
    }

    private fun maybeFlushCsvRow() {
        val w = csvWriter ?: return
        val rmc = pendingRmc
        val gga = pendingGga
        // Wait until we have either both halves (best — full row) or
        // long enough that the missing half won't come (we just flush
        // with what we have on the next sentence anyway). Cheap heuristic:
        // flush on every GGA; clear pending after writing so we don't
        // double-emit. If only RMC arrives (GGA disabled), flush on RMC
        // alone after a 100 ms grace.
        val flush = gga != null || (rmc != null && (System.nanoTime() / 1_000_000L) - csvStartMonoMs > 100)
        if (!flush) return
        val utc = (gga?.utc ?: rmc?.utc).orEmpty()
        val lat = gga?.latDeg ?: rmc?.latDeg
        val lon = gga?.lonDeg ?: rmc?.lonDeg
        val alt = gga?.altM
        // Only log speed/course from an RMC the receiver marks valid
        // (status A). While reacquiring a fix, u-blox keeps emitting
        // speed-over-ground on VOID (status V) sentences — a burst of those
        // fantasy values became "Top 27.1 km/h" on the ~6 km/h 11.7.2026
        // Ermioni session.
        val speed = rmc?.takeIf { it.statusValid }?.speedKmh
        val course = rmc?.takeIf { it.statusValid }?.courseDeg
        val fix = gga?.fixQuality ?: 0
        val sats = gga?.numSat ?: 0
        val hdop = gga?.hdop
        val ticks = (System.nanoTime() / 1_000_000L - csvStartMonoMs) / 10L
        try {
            val row = buildString {
                append(ticks).append(',')
                append(utc).append(',')
                appendDouble(lat).append(',')
                appendDouble(lon).append(',')
                appendDouble(alt).append(',')
                appendDouble(speed).append(',')
                appendDouble(course).append(',')
                append(fix).append(',')
                append(sats).append(',')
                appendDouble(hdop)
                append('\n')
            }
            w.write(row)
            // Mirror the same bytes into Download/MovementLogger/ for
            // Google Files visibility. Best-effort; never break the
            // canonical internal log on a publish error.
            val pubName = csvFile?.name
            if (pubName != null) {
                val rowBytes = row.toByteArray(Charsets.UTF_8)
                publicMirror?.appendAt(pubName, csvPublicOffset, rowBytes)
                csvPublicOffset += rowBytes.size
            }
            _state.update { it.copy(loggedRows = it.loggedRows + 1) }
            updateLiveStats(ticks, speed, fix, lat, lon)
        } catch (e: Exception) {
            _state.update { it.copy(status = "CSV write failed: ${e.message}") }
        }
        pendingRmc = null
        pendingGga = null
    }

    private fun StringBuilder.appendDouble(v: Double?): StringBuilder {
        if (v == null || v.isNaN()) return this
        // 6 decimals is plenty for lat/lon and avoids "1.0E-5" exponent
        // notation that breaks the desktop CSV parser.
        append(String.format(Locale.US, "%.6f", v))
        return this
    }
}

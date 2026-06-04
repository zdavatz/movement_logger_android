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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _state = MutableStateFlow(UbloxUiState())
    val state: StateFlow<UbloxUiState> = _state.asStateFlow()

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

    private val dateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        reader = UbloxGpsReader(usbManager!!, this)
        registerPermissionReceiver()
        refreshDevice()
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
            w.write("Time [10ms],UTC,Lat [deg],Lon [deg],Alt [m],SpeedKMh,Course [deg],Fix,NumSat,HDOP\n")
            w.flush()
            csvWriter = w
            csvFile = file
            csvStartMonoMs = System.nanoTime() / 1_000_000L
            _state.update {
                it.copy(isLogging = true, logPath = file.absolutePath, loggedRows = 0L)
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = "CSV log open failed: ${e.message}") }
        }
    }

    fun stopLogging() {
        val w = csvWriter ?: return
        try { w.flush(); w.close() } catch (_: Exception) {}
        csvWriter = null
        _state.update { it.copy(isLogging = false) }
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
                speedKmh = fix.speedKmh ?: it.speedKmh,
                courseDeg = fix.courseDeg ?: it.courseDeg,
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
        val speed = rmc?.speedKmh
        val course = rmc?.courseDeg
        val fix = gga?.fixQuality ?: 0
        val sats = gga?.numSat ?: 0
        val hdop = gga?.hdop
        val ticks = (System.nanoTime() / 1_000_000L - csvStartMonoMs) / 10L
        try {
            w.write(buildString {
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
            })
            _state.update { it.copy(loggedRows = it.loggedRows + 1) }
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

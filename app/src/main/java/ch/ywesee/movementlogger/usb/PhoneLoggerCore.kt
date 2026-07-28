package ch.ywesee.movementlogger.usb

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import ch.ywesee.movementlogger.data.PublicMirror
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
import java.util.TimeZone

/** UI snapshot of the phone logger — see [PhoneLoggerCore]. */
data class PhoneLoggerUiState(
    val running: Boolean = false,
    /** Non-null only on failures (file open / writer errors). */
    val error: String? = null,
    val hasBaro: Boolean = true,
    // --- most-recent phone fix ---
    val fixQuality: Int = 0,
    val numSat: Int = 0,
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val speedKmh: Double? = null,
    val altM: Double? = null,
    val accuracyM: Double? = null,
    // --- row counters + file names ---
    val sensRows: Long = 0L,
    val gpsRows: Long = 0L,
    val sensName: String? = null,
    val gpsName: String? = null,
    val startedAtMs: Long = 0L,
)

/**
 * Box-replacement logger using the phone's own sensors: GPS (via
 * [PhoneLoggerService]'s LocationManager) plus accel / gyro / mag / baro
 * (SensorManager, ~100 Hz IMU), written as the same Sens/Gps CSV pair the
 * box firmware logs — `SensPhone_*.csv` + `GpsPhone_*.csv`. The names
 * start with "Sens"/"Gps" so the Replay tab's local pickers list them,
 * and the schemas match [ch.ywesee.movementlogger.data.CsvParsers]
 * verbatim, so the whole Replay / ride-map pipeline works unchanged.
 *
 * Timebase: both files share one tick clock — `Time [10ms]` derived from
 * `elapsedRealtimeNanos` (SensorEvent timestamps and
 * `Location.elapsedRealtimeNanos` are the same CLOCK_BOOTTIME domain), so
 * sensor and GPS rows align with zero cross-clock skew. A
 * `# SYNC epoch_ms=… tick_ms=0` anchor after each header maps tick 0 to
 * the phone wall clock — the same marker the firmware writes on SET_TIME —
 * which gives Replay its drift-free "Phone-clock sync" alignment (shared
 * clock domain with the video's `creation_time`).
 *
 * Units are converted to the box CSV's: acc m/s² → mg, gyro rad/s → mdps,
 * mag µT → mgauss (×10), pressure hPa = mB directly. The temperature
 * column is a constant 20 °C — phones have no ambient thermometer, and
 * `Baro.heightAboveWaterM` only needs a plausible absolute Kelvin value.
 *
 * IMPORTANT: `CsvParsers.parseSensorStream` is strict — one blank field
 * fails the entire file — so no sens row is written until every present
 * sensor (gyro, mag, and baro when the phone has one) has delivered its
 * first sample. Missing barometer degrades to a constant 1013.25 mB
 * (angles/speed still work; height panels stay flat).
 *
 * Threading: sensor callbacks land on a dedicated [HandlerThread]; GPS
 * fixes are posted onto the same handler, so all file IO is
 * single-threaded without locks (same pattern as the BLE worker).
 */
@SuppressLint("StaticFieldLeak")
object PhoneLoggerCore : SensorEventListener {

    private const val TAG = "PhoneLoggerCore"

    /** ~100 Hz IMU — the box's Sens rate; well under the 200 Hz
     *  HIGH_SAMPLING_RATE_SENSORS permission threshold. */
    private const val IMU_PERIOD_US = 10_000
    /** Baro at ~10 Hz; the row writer just carries the latest value. */
    private const val BARO_PERIOD_US = 100_000

    private const val GRAVITY = 9.80665
    private const val RAD_TO_MDPS = 180.0 / Math.PI * 1000.0
    /** No ambient thermometer on phones — constant for the `T ['C]` column. */
    private const val TEMP_C = 20.0
    /** Sea-level standard, used only when the phone has no barometer. */
    private const val NO_BARO_MB = 1013.25

    /** Batch the 100 Hz sens rows before each public-mirror publish —
     *  MediaStore opens an output stream per append, far too heavy per row. */
    private const val MIRROR_FLUSH_BYTES = 16 * 1024

    /** Update the row-counter state every N sens rows (~2 Hz at 100 Hz)
     *  so the StateFlow doesn't recompose the UI at sample rate. */
    private const val STATE_EVERY_ROWS = 50

    private val _state = MutableStateFlow(PhoneLoggerUiState())
    val state: StateFlow<PhoneLoggerUiState> = _state.asStateFlow()

    /** Name of the in-flight GPS CSV, so [UbloxGpsCore.refreshRecordings]
     *  can exclude it (its size changes every second — listing it would
     *  re-parse the growing file on every stats pass). */
    @Volatile var activeGpsName: String? = null
        private set

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var publicMirror: PublicMirror? = null

    private val thread by lazy { HandlerThread("phone-logger").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    private var sensWriter: BufferedWriter? = null
    private var gpsWriter: BufferedWriter? = null
    private var sensFile: File? = null
    private var gpsFile: File? = null
    private var startElapsedNs: Long = 0L

    // Latest cached values from the non-accel sensors (NaN = not yet seen).
    private var gyro = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
    private var mag = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
    private var pressureMb = Double.NaN
    private var hasBaro = false

    private var sensRows = 0L
    private var gpsRows = 0L

    // Public-mirror offsets + sens-row batch buffer.
    private var sensMirrorOffset = 0L
    private var gpsMirrorOffset = 0L
    private val sensMirrorBuf = StringBuilder()

    private val dateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val utcFmt = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** True while a recording is open — checked by [PhoneLoggerService]. */
    val isRunning: Boolean get() = _state.value.running

    /** Start a recording. Caller must hold ACCESS_FINE_LOCATION. */
    fun start(ctx: Context) {
        if (isRunning) return
        val app = ctx.applicationContext
        appContext = app
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.update { it.copy(error = "Location permission required") }
            return
        }
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        if (publicMirror == null) publicMirror = PublicMirror(app)

        val stamp = dateFmt.format(Date())
        val sens = File(dir, "SensPhone_$stamp.csv")
        val gps = File(dir, "GpsPhone_$stamp.csv")
        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBaro = baro != null
        try {
            // Both headers mirror the box firmware's spaced-name schema, so
            // CsvParsers reads them with tickDiv = 1 (already 10 ms units).
            val epochMs = System.currentTimeMillis()
            startElapsedNs = SystemClock.elapsedRealtimeNanos()
            val sync = "# SYNC epoch_ms=$epochMs tick_ms=0\n"
            val sensHeader = "Time [10ms],AccX [mg],AccY [mg],AccZ [mg]," +
                "GyroX [mdps],GyroY [mdps],GyroZ [mdps]," +
                "MagX [mgauss],MagY [mgauss],MagZ [mgauss],P [mB],T ['C]\n"
            val gpsHeader =
                "Time [10ms],UTC,Lat [deg],Lon [deg],Alt [m],SpeedKMh,Course [deg],Fix,NumSat,HDOP\n"

            val sw = BufferedWriter(FileWriter(sens, false), 64 * 1024)
            sw.write(sensHeader); sw.write(sync); sw.flush()
            val gw = BufferedWriter(FileWriter(gps, false))
            gw.write(gpsHeader); gw.write(sync); gw.flush()
            sensWriter = sw
            gpsWriter = gw
            sensFile = sens
            gpsFile = gps
            sensRows = 0L
            gpsRows = 0L
            gyro = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
            mag = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
            pressureMb = Double.NaN
            sensMirrorBuf.setLength(0)
            publicMirror?.delete(sens.name)
            publicMirror?.delete(gps.name)
            sensMirrorOffset = 0L
            gpsMirrorOffset = 0L
            mirrorSens(sensHeader + sync)
            mirrorGps(gpsHeader + sync)
            activeGpsName = gps.name
        } catch (e: Exception) {
            _state.update { it.copy(error = "CSV open failed: ${e.message}") }
            return
        }

        listOf(
            Sensor.TYPE_ACCELEROMETER to IMU_PERIOD_US,
            Sensor.TYPE_GYROSCOPE to IMU_PERIOD_US,
            Sensor.TYPE_MAGNETIC_FIELD to IMU_PERIOD_US,
            Sensor.TYPE_PRESSURE to BARO_PERIOD_US,
        ).forEach { (type, period) ->
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, period, handler) }
        }
        _state.value = PhoneLoggerUiState(
            running = true,
            hasBaro = hasBaro,
            sensName = sens.name,
            gpsName = gps.name,
            startedAtMs = System.currentTimeMillis(),
        )
        PhoneLoggerService.start(app)
        Log.i(TAG, "recording started: ${sens.name} + ${gps.name} (baro=$hasBaro)")
    }

    fun stop() {
        if (!isRunning) return
        _state.update { it.copy(running = false) }
        sensorManager?.unregisterListener(this)
        handler.post {
            try { sensWriter?.flush(); sensWriter?.close() } catch (_: Exception) {}
            try { gpsWriter?.flush(); gpsWriter?.close() } catch (_: Exception) {}
            sensWriter = null
            gpsWriter = null
            flushSensMirror(force = true)
            activeGpsName = null
            appContext?.let { PhoneLoggerService.stop(it) }
            // The finished GpsPhone_* now shows up in the recordings list.
            UbloxGpsCore.refreshRecordings()
            Log.i(TAG, "recording stopped: $sensRows sens rows, $gpsRows gps rows")
        }
    }

    /** Phone fix from [PhoneLoggerService] — posted onto the writer thread
     *  so all file IO stays single-threaded. */
    fun onPhoneFix(location: Location, satsUsed: Int) {
        if (!isRunning) return
        handler.post { writeGpsRow(location, satsUsed) }
    }

    // --- SensorEventListener (runs on the phone-logger handler) ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = event.values[0] * RAD_TO_MDPS
                gyro[1] = event.values[1] * RAD_TO_MDPS
                gyro[2] = event.values[2] * RAD_TO_MDPS
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // µT → mgauss: 1 µT = 10 mG.
                mag[0] = event.values[0] * 10.0
                mag[1] = event.values[1] * 10.0
                mag[2] = event.values[2] * 10.0
            }
            Sensor.TYPE_PRESSURE -> pressureMb = event.values[0].toDouble()
            Sensor.TYPE_ACCELEROMETER -> writeSensRow(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- row writers (phone-logger thread only) ---

    private fun writeSensRow(event: SensorEvent) {
        val w = sensWriter ?: return
        // Strict parser downstream — hold rows until every present sensor
        // has reported once so no field is ever blank.
        if (gyro[0].isNaN() || mag[0].isNaN() || (hasBaro && pressureMb.isNaN())) return
        val ticks = (event.timestamp - startElapsedNs) / 10_000_000L
        if (ticks < 0) return
        val p = if (hasBaro) pressureMb else NO_BARO_MB
        val row = buildString(96) {
            append(ticks)
            appendField(event.values[0] / GRAVITY * 1000.0)
            appendField(event.values[1] / GRAVITY * 1000.0)
            appendField(event.values[2] / GRAVITY * 1000.0)
            appendField(gyro[0]); appendField(gyro[1]); appendField(gyro[2])
            appendField(mag[0]); appendField(mag[1]); appendField(mag[2])
            append(',').append(String.format(Locale.US, "%.2f", p))
            append(',').append(String.format(Locale.US, "%.1f", TEMP_C))
            append('\n')
        }
        try {
            w.write(row)
        } catch (e: Exception) {
            _state.update { it.copy(error = "Sens write failed: ${e.message}") }
            return
        }
        sensMirrorBuf.append(row)
        if (sensMirrorBuf.length >= MIRROR_FLUSH_BYTES) flushSensMirror(force = false)
        sensRows++
        if (sensRows % STATE_EVERY_ROWS == 0L) {
            _state.update { it.copy(sensRows = sensRows) }
        }
    }

    private fun writeGpsRow(location: Location, satsUsed: Int) {
        val w = gpsWriter ?: return
        val ticks = (location.elapsedRealtimeNanos - startElapsedNs) / 10_000_000L
        if (ticks < 0) return
        val utc = utcFmt.format(Date(location.time)) +
            String.format(Locale.US, ".%02d", (location.time % 1000) / 10)
        // GNSS altitude is ellipsoidal; prefer MSL when the platform has it
        // (API 34+) so the column means the same as the box's NMEA altitude.
        val alt = when {
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude() -> location.mslAltitudeMeters
            location.hasAltitude() -> location.altitude
            else -> Double.NaN
        }
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else Double.NaN
        val course = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN
        // The schema column is HDOP; approximate from the platform accuracy
        // with the usual ~5 m UERE so downstream quality gates (hdop ≤ 50)
        // keep their meaning.
        val hdop = if (acc.isNaN()) Double.NaN else acc / 5.0
        val row = buildString(96) {
            append(ticks).append(',').append(utc)
            appendField(location.latitude, "%.6f")
            appendField(location.longitude, "%.6f")
            appendField(alt, "%.1f")
            appendField(speedKmh, "%.2f")
            appendField(course, "%.1f")
            append(",1,").append(satsUsed.coerceAtLeast(0))
            appendField(hdop, "%.2f")
            append('\n')
        }
        try {
            w.write(row)
            w.flush()
        } catch (e: Exception) {
            _state.update { it.copy(error = "Gps write failed: ${e.message}") }
            return
        }
        mirrorGps(row)
        gpsRows++
        _state.update {
            it.copy(
                gpsRows = gpsRows,
                sensRows = sensRows,
                fixQuality = 1,
                numSat = satsUsed.coerceAtLeast(0),
                latDeg = location.latitude,
                lonDeg = location.longitude,
                speedKmh = if (speedKmh.isNaN()) it.speedKmh else speedKmh,
                altM = if (alt.isNaN()) it.altM else alt,
                accuracyM = if (acc.isNaN()) it.accuracyM else acc,
            )
        }
    }

    // --- Download/MovementLogger/ mirror (best-effort, like UbloxGpsCore) ---

    private fun mirrorSens(text: String) {
        sensMirrorBuf.append(text)
        flushSensMirror(force = true)
    }

    private fun flushSensMirror(force: Boolean) {
        if (sensMirrorBuf.isEmpty()) return
        if (!force && sensMirrorBuf.length < MIRROR_FLUSH_BYTES) return
        val name = sensFile?.name ?: return
        val bytes = sensMirrorBuf.toString().toByteArray(Charsets.UTF_8)
        sensMirrorBuf.setLength(0)
        publicMirror?.appendAt(name, sensMirrorOffset, bytes)
        sensMirrorOffset += bytes.size
    }

    private fun mirrorGps(text: String) {
        val name = gpsFile?.name ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        publicMirror?.appendAt(name, gpsMirrorOffset, bytes)
        gpsMirrorOffset += bytes.size
    }

    /** Comma + fixed-point value; empty field for NaN (GPS columns only —
     *  the GPS parser tolerates blanks, the sensor parser does not). */
    private fun StringBuilder.appendField(v: Double, fmt: String = "%.1f"): StringBuilder {
        append(',')
        if (!v.isNaN()) append(String.format(Locale.US, fmt, v))
        return this
    }
}

package ch.ywesee.movementlogger.data

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * SD-card recording parsers — Kotlin port of `io.rs` from the desktop project.
 *
 * Three file kinds the firmware writes per logging session:
 *
 * - `Sens*.csv`  ~100 Hz IMU + baro samples (12 columns)
 * - `Gps*.csv`   ~10 Hz GPS fixes (10 columns) — firmware ≥ Apr 2026.
 *                Older sessions logged at ~1 Hz; parsers handle both
 *                cadences transparently (Replay/export use whatever
 *                density the file has).
 * - `Bat*.csv`   battery voltage / SOC / current (4 columns)
 *
 * All three start with a `Time [10ms]` column — ThreadX ticks where one
 * tick is 10 ms. Pre-22.4.2026 firmware called it `Time [mS]`; both
 * spellings are accepted.
 */

/** Single sensor sample. Units match the CSV verbatim (mg / mdps / mgauss / hPa / °C). */
data class SensorRow(
    val ticks: Double,
    val accX: Double,
    val accY: Double,
    val accZ: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double,
    val magX: Double,
    val magY: Double,
    val magZ: Double,
    val pressureMb: Double,
    val temperatureC: Double,
)

/** Single GPS fix row. UTC kept as the raw `hhmmss.ss` string for display + alignment. */
data class GpsRow(
    val ticks: Double,
    val utc: String,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedKmhModule: Double,
    val courseDeg: Double,
    val fix: Int,
    val numSat: Int,
    val hdop: Double,
)

/** Battery row. Units match the CSV — voltage in mV, SOC in 0.1 %, current in 100 µA. */
data class BatteryRow(
    val ticks: Double,
    val voltageMv: Int,
    val socTenthPct: Int,
    val currentHundredUa: Int,
)

/**
 * A host-clock time-sync anchor the firmware stamps into Sens/Gps CSVs on
 * each BLE connect (`SET_TIME` 0x08): a `# SYNC epoch_ms=… tick_ms=…`
 * comment line pairing the phone's absolute wall-clock millis with the
 * box's free-running ms counter. Because the box has no RTC, these anchors
 * are the *only* drift-free, GPS-independent way to map a logged row's tick
 * to absolute time — and they share the phone's clock domain with the
 * replay video's `creation_time`, eliminating cross-clock skew.
 */
data class SyncAnchor(
    /**
     * Box tick in the SAME unit as `SensorRow.ticks` / `GpsRow.ticks`
     * (10 ms units), so it slots straight into the abs-time interpolation.
     */
    val ticks: Double,
    /** Phone wall-clock epoch milliseconds the host pushed at this tick. */
    val epochMs: Long,
)

object CsvParsers {

    @Throws(IOException::class)
    fun parseSensorFile(file: File): List<SensorRow> = file.inputStream().use(::parseSensorStream)

    @Throws(IOException::class)
    fun parseGpsFile(file: File): List<GpsRow> = file.inputStream().use(::parseGpsStream)

    @Throws(IOException::class)
    fun parseBatteryFile(file: File): List<BatteryRow> = file.inputStream().use(::parseBatteryStream)

    /** Best-effort anchor scan of a file; [] on any IO error or legacy file. */
    fun parseSyncAnchorsFile(file: File): List<SyncAnchor> = try {
        file.inputStream().use(::parseSyncAnchors)
    } catch (_: IOException) {
        emptyList()
    }

    /**
     * Pull every `# SYNC epoch_ms=<u64> tick_ms=<u32>` marker out of a Sens
     * or Gps CSV (written by the firmware's SET_TIME handler). `tick_ms` is
     * the box's raw `HAL_GetTick()` ms — the same clock as the `ms`/`Time`
     * column — so we divide by the file's tick divisor to land in the
     * row-tick (10 ms) unit. The data-row parsers naturally skip these
     * comment lines (the `#` field fails the float parse), so this is a
     * cheap separate pass that never disturbs row parsing. Returns [] for
     * files from firmware that predates the marker (legacy / never-connected).
     */
    fun parseSyncAnchors(input: InputStream): List<SyncAnchor> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        // Markers only appear in the compact `ms,…` schema (raw ms → ÷10 →
        // 10 ms ticks). The legacy spaced header ("Time [10ms]", …) is
        // already in 10 ms units, so its divisor is 1 (it never carries
        // markers in practice, but detect it to stay correct regardless).
        var tickDiv = 10.0
        var sawHeader = false
        val out = ArrayList<SyncAnchor>()
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!sawHeader) {
                sawHeader = true
                if (line.lowercase().contains("time [")) tickDiv = 1.0
                continue
            }
            if (!line.startsWith("#") || !line.contains("SYNC")) continue
            var epochMs: Long? = null
            var tickMs: Double? = null
            for (tok in line.split(' ', '\t').filter { it.isNotEmpty() }) {
                when {
                    tok.startsWith("epoch_ms=") -> epochMs = tok.removePrefix("epoch_ms=").toLongOrNull()
                    tok.startsWith("tick_ms=") -> tickMs = tok.removePrefix("tick_ms=").toDoubleOrNull()
                }
            }
            val e = epochMs
            val t = tickMs
            if (e != null && t != null && e > 0L) out.add(SyncAnchor(t / tickDiv, e))
        }
        // Sort + dedupe by tick so the abs-time interpolation gets a clean,
        // monotone anchor curve even if connects produced out-of-order or
        // duplicate markers.
        out.sortBy { it.ticks }
        val deduped = ArrayList<SyncAnchor>(out.size)
        for (a in out) if (deduped.lastOrNull()?.ticks != a.ticks) deduped.add(a)
        return deduped
    }

    @Throws(IOException::class)
    fun parseSensorStream(input: InputStream): List<SensorRow> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine() ?: throw IOException("sensor csv: empty file")
        val cols = HeaderMap(header)
        val iT = cols.idxAny("Time [10ms]", "Time [mS]")
        val iAx = cols.idxAny("AccX [mg]")
        val iAy = cols.idxAny("AccY [mg]")
        val iAz = cols.idxAny("AccZ [mg]")
        val iGx = cols.idxAny("GyroX [mdps]")
        val iGy = cols.idxAny("GyroY [mdps]")
        val iGz = cols.idxAny("GyroZ [mdps]")
        val iMx = cols.idxAny("MagX [mgauss]")
        val iMy = cols.idxAny("MagY [mgauss]")
        val iMz = cols.idxAny("MagZ [mgauss]")
        val iP = cols.idxAny("P [mB]")
        val iTc = cols.idxAny("T ['C]")

        val out = ArrayList<SensorRow>(8192)
        var lineNo = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            val r = splitTrim(line)
            try {
                out.add(
                    SensorRow(
                        ticks = parseDouble(r, iT),
                        accX = parseDouble(r, iAx),
                        accY = parseDouble(r, iAy),
                        accZ = parseDouble(r, iAz),
                        gyroX = parseDouble(r, iGx),
                        gyroY = parseDouble(r, iGy),
                        gyroZ = parseDouble(r, iGz),
                        magX = parseDouble(r, iMx),
                        magY = parseDouble(r, iMy),
                        magZ = parseDouble(r, iMz),
                        pressureMb = parseDouble(r, iP),
                        temperatureC = parseDouble(r, iTc),
                    )
                )
            } catch (e: Exception) {
                throw IOException("sensor csv row $lineNo: ${e.message}", e)
            }
        }
        return out
    }

    @Throws(IOException::class)
    fun parseGpsStream(input: InputStream): List<GpsRow> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine() ?: throw IOException("gps csv: empty file")
        val cols = HeaderMap(header)
        // Post-22.4.2026 firmware switched to compact column names. `ms` is
        // raw milliseconds, so divide by 10 to stay in 10ms ticks. (Exact
        // mirror of the iOS parser, incl. the bracketed u-blox/watch names.)
        val iMs = cols.idxOrNull("ms")
        val iT = iMs ?: cols.idxAny("Time [10ms]", "Time [mS]")
        val tickDiv = if (iMs != null) 10.0 else 1.0
        val iUtc = cols.idxAny("UTC", "utc")
        // The USB u-blox logger (`UbloxGpsCore`) and the iOS watch logger
        // write bracketed column names — `Lat [deg]` / `Lon [deg]` /
        // `SpeedKMh` — so accept those alongside the box firmware's
        // `Lat`/`lat` and `speed_kmh`.
        val iLat = cols.idxAny("Lat", "lat", "Lat [deg]")
        val iLon = cols.idxAny("Lon", "lon", "Lon [deg]")
        val iAlt = cols.idxAny("Alt [m]", "alt_m")
        val iSpd = cols.idxAny("Speed [km/h]", "speed_kmh", "SpeedKMh")
        val iCrs = cols.idxAny("Course [deg]", "course_deg")
        val iFix = cols.idxAny("Fix", "fix_q")
        val iSat = cols.idxAny("NumSat", "nsat")
        val iHdp = cols.idxAny("HDOP", "hdop")

        val out = ArrayList<GpsRow>(2048)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val r = splitTrim(line)
            // Skip corrupted rows (torn tail of a live-mirrored file, `#`
            // comment lines) instead of failing the whole file — iOS parity.
            // Only ticks + lat + lon are essential; the USB u-blox logger
            // legitimately leaves alt/speed/course/hdop blank on rows where
            // only one NMEA sentence contributed (→ NaN / 0 defaults).
            try {
                out.add(
                    GpsRow(
                        ticks = parseDouble(r, iT) / tickDiv,
                        utc = r[iUtc],
                        lat = parseDouble(r, iLat),
                        lon = parseDouble(r, iLon),
                        altM = parseDoubleOrNan(r, iAlt),
                        speedKmhModule = parseDoubleOrNan(r, iSpd),
                        courseDeg = parseDoubleOrNan(r, iCrs),
                        fix = parseIntOrZero(r, iFix),
                        numSat = parseIntOrZero(r, iSat),
                        hdop = parseDoubleOrNan(r, iHdp),
                    )
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    @Throws(IOException::class)
    fun parseBatteryStream(input: InputStream): List<BatteryRow> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine() ?: throw IOException("battery csv: empty file")
        val cols = HeaderMap(header)
        val iT = cols.idxAny("Time [10ms]", "Time [mS]")
        val iV = cols.idxAny("Voltage [mV]")
        val iSoc = cols.idxAny("SOC [0.1%]")
        val iCur = cols.idxAny("Current [100uA]")

        val out = ArrayList<BatteryRow>(2048)
        var lineNo = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            val r = splitTrim(line)
            try {
                out.add(
                    BatteryRow(
                        ticks = parseDouble(r, iT),
                        voltageMv = parseInt(r, iV),
                        socTenthPct = parseInt(r, iSoc),
                        currentHundredUa = parseInt(r, iCur),
                    )
                )
            } catch (e: Exception) {
                throw IOException("battery csv row $lineNo: ${e.message}", e)
            }
        }
        return out
    }
}

private class HeaderMap(headerLine: String) {
    private val map: Map<String, Int> =
        splitTrim(headerLine).withIndex().associate { (i, name) -> name to i }

    fun idxOrNull(name: String): Int? = map[name]

    fun idxAny(vararg names: String): Int {
        for (n in names) map[n]?.let { return it }
        throw IOException("missing column, expected one of ${names.toList()}; got ${map.keys}")
    }
}

private fun splitTrim(line: String): List<String> = line.split(',').map { it.trim() }

private fun parseDouble(row: List<String>, idx: Int): Double {
    val s = row.getOrNull(idx) ?: throw IOException("missing column at index $idx")
    return s.toDoubleOrNull() ?: throw IOException("not a float: \"$s\"")
}

private fun parseInt(row: List<String>, idx: Int): Int {
    val s = row.getOrNull(idx) ?: throw IOException("missing column at index $idx")
    return s.toIntOrNull() ?: throw IOException("not an int: \"$s\"")
}

private fun parseDoubleOrNan(row: List<String>, idx: Int): Double =
    row.getOrNull(idx)?.toDoubleOrNull() ?: Double.NaN

private fun parseIntOrZero(row: List<String>, idx: Int): Int =
    row.getOrNull(idx)?.toIntOrNull() ?: 0

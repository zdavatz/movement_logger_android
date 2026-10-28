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
/** One-second mean-acceleration bin from [CsvParsers.accBinsFromSensorStream];
 *  `bin` = 10ms-tick ÷ 100, means in mg. */
data class AccBin(
    val bin: Int,
    val n: Int,
    val meanXMg: Double,
    val meanYMg: Double,
    val meanZMg: Double,
)

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
            parseSyncLine(line, tickDiv)?.let(out::add)
        }
        return sortDedupeAnchors(out)
    }

    /**
     * One `# SYNC epoch_ms=… tick_ms=…` comment line → anchor in row-tick
     * units (`tick_ms ÷ tickDiv`), or null for any other line. Shared with
     * the Session loader, which meets these lines inside its single
     * streaming pass over the sensor file.
     */
    internal fun parseSyncLine(line: String, tickDiv: Double): SyncAnchor? {
        if (!line.startsWith("#") || !line.contains("SYNC")) return null
        var epochMs: Long? = null
        var tickMs: Double? = null
        for (tok in line.split(' ', '\t').filter { it.isNotEmpty() }) {
            when {
                tok.startsWith("epoch_ms=") -> epochMs = tok.removePrefix("epoch_ms=").toLongOrNull()
                tok.startsWith("tick_ms=") -> tickMs = tok.removePrefix("tick_ms=").toDoubleOrNull()
            }
        }
        val e = epochMs ?: return null
        val t = tickMs ?: return null
        return if (e > 0L) SyncAnchor(t / tickDiv, e) else null
    }

    /**
     * Sort + dedupe by tick so the abs-time interpolation gets a clean,
     * monotone anchor curve even if connects produced out-of-order or
     * duplicate markers.
     */
    internal fun sortDedupeAnchors(anchors: List<SyncAnchor>): List<SyncAnchor> {
        val sorted = anchors.sortedBy { it.ticks }
        val deduped = ArrayList<SyncAnchor>(sorted.size)
        for (a in sorted) if (deduped.lastOrNull()?.ticks != a.ticks) deduped.add(a)
        return deduped
    }

    /**
     * Column indices of a `Sens*` header, both schemas: the spaced one
     * (`Time [10ms]`, `AccX [mg]`, `P [mB]` …, already in 10 ms ticks)
     * and the compact one the firmware writes since 22.4.2026
     * (`ms,ax_mg,…,p_hPa,t_C` — raw ms, so `tickDiv` = 10). Legacy
     * `Time [mS]` keeps tickDiv 1 as before.
     */
    internal class SensorColumns(header: String) {
        private val cols = HeaderMap(header)
        private val iMs = cols.idxOrNull("ms")
        val t = iMs ?: cols.idxAny("Time [10ms]", "Time [mS]")
        val tickDiv = if (iMs != null) 10.0 else 1.0
        val ax = cols.idxAny("AccX [mg]", "ax_mg")
        val ay = cols.idxAny("AccY [mg]", "ay_mg")
        val az = cols.idxAny("AccZ [mg]", "az_mg")
        val gx = cols.idxAny("GyroX [mdps]", "gx_mdps")
        val gy = cols.idxAny("GyroY [mdps]", "gy_mdps")
        val gz = cols.idxAny("GyroZ [mdps]", "gz_mdps")
        val mx = cols.idxAny("MagX [mgauss]", "mx_mg")
        val my = cols.idxAny("MagY [mgauss]", "my_mg")
        val mz = cols.idxAny("MagZ [mgauss]", "mz_mg")
        val p = cols.idxAny("P [mB]", "p_hPa")
        val tc = cols.idxAny("T ['C]", "t_C")
    }

    @Throws(IOException::class)
    fun parseSensorStream(input: InputStream): List<SensorRow> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine() ?: throw IOException("sensor csv: empty file")
        val c = SensorColumns(header)

        val out = ArrayList<SensorRow>(8192)
        var lineNo = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            // `# SYNC …` markers (firmware SET_TIME / PhoneLoggerCore) — this
            // parser is strict per-row, so comments must be skipped explicitly.
            if (line.startsWith("#")) continue
            val r = splitTrim(line)
            try {
                out.add(
                    SensorRow(
                        ticks = parseDouble(r, c.t) / c.tickDiv,
                        accX = parseDouble(r, c.ax),
                        accY = parseDouble(r, c.ay),
                        accZ = parseDouble(r, c.az),
                        gyroX = parseDouble(r, c.gx),
                        gyroY = parseDouble(r, c.gy),
                        gyroZ = parseDouble(r, c.gz),
                        magX = parseDouble(r, c.mx),
                        magY = parseDouble(r, c.my),
                        magZ = parseDouble(r, c.mz),
                        pressureMb = parseDouble(r, c.p),
                        temperatureC = parseDouble(r, c.tc),
                    )
                )
            } catch (e: Exception) {
                throw IOException("sensor csv row $lineNo: ${e.message}", e)
            }
        }
        return out
    }

    /**
     * Per-second mean-acceleration bins of a `Sens*` CSV, feeding
     * [RideMapRenderer]'s IMU-informed acceleration gate. STREAMING on
     * purpose: the 29.7.2026 SUP session's SensPhone file is 86 MB /
     * 1.1 M rows — a full [parseSensorStream] list would be ~100 MB of
     * heap on-device (the 11.7.2026 OOM lesson, again). Only the tick +
     * acc columns are read; malformed rows are skipped, not fatal.
     * `Time [mS]` (legacy, raw milliseconds) is normalised to 10 ms ticks
     * so bins line up with [GpsRow.ticks]. Bin key = tick ÷ 100 (1 s).
     */
    @Throws(IOException::class)
    fun accBinsFromSensorStream(input: InputStream): List<AccBin> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine() ?: throw IOException("sensor csv: empty file")
        val cols = HeaderMap(header)
        // Raw-millisecond time columns (legacy `Time [mS]`, compact `ms`)
        // are normalised to 10 ms ticks.
        val iMsLegacy = cols.idxOrNull("Time [mS]") ?: cols.idxOrNull("ms")
        val iT = iMsLegacy ?: cols.idxAny("Time [10ms]")
        val tickDiv = if (iMsLegacy != null) 10.0 else 1.0
        val iAx = cols.idxAny("AccX [mg]", "ax_mg")
        val iAy = cols.idxAny("AccY [mg]", "ay_mg")
        val iAz = cols.idxAny("AccZ [mg]", "az_mg")
        val maxIdx = maxOf(iT, iAx, iAy, iAz)
        val acc = HashMap<Int, DoubleArray>()
        // Field walk instead of split(): at 1.1 M rows × 12 columns a full
        // split allocates gigabytes across a pass — only the four needed
        // fields are ever substring'd, and the line is abandoned at the
        // last one.
        val fields = DoubleArray(maxIdx + 1)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty() || line[0] == '#') continue
            var start = 0
            var idx = 0
            var ok = true
            while (idx <= maxIdx) {
                val comma = line.indexOf(',', start)
                val end = if (comma < 0) line.length else comma
                if (idx == iT || idx == iAx || idx == iAy || idx == iAz) {
                    val v = fastDoubleOrNull(line.substring(start, end).trim())
                    if (v == null) { ok = false; break }
                    fields[idx] = v
                }
                if (comma < 0 && idx < maxIdx) { ok = false; break }
                start = end + 1
                idx++
            }
            if (!ok) continue
            val bin = ((fields[iT] / tickDiv) / 100.0).toInt()
            val a = acc.getOrPut(bin) { DoubleArray(4) }
            a[0] += 1.0; a[1] += fields[iAx]; a[2] += fields[iAy]; a[3] += fields[iAz]
        }
        return acc.entries.sortedBy { it.key }.map { (bin, a) ->
            AccBin(bin, a[0].toInt(), a[1] / a[0], a[2] / a[0], a[3] / a[0])
        }
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
            if (line.startsWith("#")) continue
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

internal class HeaderMap(headerLine: String) {
    private val map: Map<String, Int> =
        splitTrim(headerLine).withIndex().associate { (i, name) -> name to i }

    fun idxOrNull(name: String): Int? = map[name]

    fun idxAny(vararg names: String): Int {
        for (n in names) map[n]?.let { return it }
        throw IOException("missing column, expected one of ${names.toList()}; got ${map.keys}")
    }
}

private fun splitTrim(line: String): List<String> = line.split(',').map { it.trim() }

/**
 * Regex-free `toDoubleOrNull`. Kotlin's stdlib version screens every call
 * through `ScreenFloatValueRegEx` — one native ICU regex match per
 * invocation. At CSV/NMEA hot-loop rates that native churn exhausted the
 * allocator after ~19 h of process uptime: Scudo aborted with `internal map
 * failure (Out of memory)` inside the GPS recordings-stats path and killed
 * the 11.7.2026 water-test recording. `Double.parseDouble` does its own
 * trim + validation; the exception path only fires on genuinely malformed
 * fields, which are rare on this wire.
 */
internal fun fastDoubleOrNull(s: String): Double? {
    if (s.isEmpty()) return null
    return try {
        java.lang.Double.parseDouble(s)
    } catch (_: NumberFormatException) {
        null
    }
}

private val POW10 = DoubleArray(16) { Math.pow(10.0, it.toDouble()) }

/**
 * Allocation-free decimal parse of `line[start, end)` — the Session
 * loader's hot loop runs this ~10 M times on a 3 h sensor file, where a
 * `substring` + [fastDoubleOrNull] per field would churn ~10 M short-lived
 * strings. Plain `[-+]digits[.digits]` is parsed inline (an exact long
 * divided by an exact power of ten, so the result equals
 * `Double.parseDouble`); anything else (exponent, > 15 digits — past exact double range) falls back
 * to [fastDoubleOrNull]. NaN for an empty or malformed field.
 */
internal fun parseFieldOrNan(line: String, start: Int, end: Int): Double {
    var s = start
    var e = end
    while (s < e && line[s] == ' ') s++
    while (e > s && line[e - 1] == ' ') e--
    if (s >= e) return Double.NaN
    var i = s
    var neg = false
    if (line[i] == '-') { neg = true; i++ } else if (line[i] == '+') i++
    var v = 0L
    var digits = 0
    var frac = 0
    var dot = false
    while (i < e) {
        val c = line[i]
        if (c in '0'..'9') {
            if (digits == 15) return fastDoubleOrNull(line.substring(s, e)) ?: Double.NaN
            v = v * 10 + (c - '0')
            digits++
            if (dot) frac++
        } else if (c == '.' && !dot) {
            dot = true
        } else {
            return fastDoubleOrNull(line.substring(s, e)) ?: Double.NaN
        }
        i++
    }
    if (digits == 0) return Double.NaN
    val d = v.toDouble() / POW10[frac]
    return if (neg) -d else d
}

private fun parseDouble(row: List<String>, idx: Int): Double {
    val s = row.getOrNull(idx) ?: throw IOException("missing column at index $idx")
    return fastDoubleOrNull(s) ?: throw IOException("not a float: \"$s\"")
}

private fun parseInt(row: List<String>, idx: Int): Int {
    val s = row.getOrNull(idx) ?: throw IOException("missing column at index $idx")
    return s.toIntOrNull() ?: throw IOException("not an int: \"$s\"")
}

private fun parseDoubleOrNan(row: List<String>, idx: Int): Double =
    row.getOrNull(idx)?.let(::fastDoubleOrNull) ?: Double.NaN

private fun parseIntOrZero(row: List<String>, idx: Int): Int =
    row.getOrNull(idx)?.toIntOrNull() ?: 0

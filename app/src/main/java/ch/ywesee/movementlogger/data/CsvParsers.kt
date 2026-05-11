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
 * - `Gps*.csv`   ~1 Hz GPS fixes (10 columns)
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

object CsvParsers {

    @Throws(IOException::class)
    fun parseSensorFile(file: File): List<SensorRow> = file.inputStream().use(::parseSensorStream)

    @Throws(IOException::class)
    fun parseGpsFile(file: File): List<GpsRow> = file.inputStream().use(::parseGpsStream)

    @Throws(IOException::class)
    fun parseBatteryFile(file: File): List<BatteryRow> = file.inputStream().use(::parseBatteryStream)

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
        val iT = cols.idxAny("Time [10ms]", "Time [mS]")
        val iUtc = cols.idxAny("UTC")
        val iLat = cols.idxAny("Lat")
        val iLon = cols.idxAny("Lon")
        val iAlt = cols.idxAny("Alt [m]")
        val iSpd = cols.idxAny("Speed [km/h]")
        val iCrs = cols.idxAny("Course [deg]")
        val iFix = cols.idxAny("Fix")
        val iSat = cols.idxAny("NumSat")
        val iHdp = cols.idxAny("HDOP")

        val out = ArrayList<GpsRow>(2048)
        var lineNo = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            val r = splitTrim(line)
            try {
                out.add(
                    GpsRow(
                        ticks = parseDouble(r, iT),
                        utc = r[iUtc],
                        lat = parseDouble(r, iLat),
                        lon = parseDouble(r, iLon),
                        altM = parseDouble(r, iAlt),
                        speedKmhModule = parseDouble(r, iSpd),
                        courseDeg = parseDouble(r, iCrs),
                        fix = parseInt(r, iFix),
                        numSat = parseInt(r, iSat),
                        hdop = parseDouble(r, iHdp),
                    )
                )
            } catch (e: Exception) {
                throw IOException("gps csv row $lineNo: ${e.message}", e)
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

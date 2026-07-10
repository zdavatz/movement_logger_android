package ch.ywesee.movementlogger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class CsvParsersTest {

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) {
            "test resource $name missing"
        }

    // ---- Sensor ----------------------------------------------------------

    @Test
    fun parsesSensorSample() {
        val rows = resource("Sens_sample.csv").use(CsvParsers::parseSensorStream)
        assertEquals(4, rows.size)
        val first = rows.first()
        assertEquals(146.0, first.ticks, 0.0)
        assertEquals(923.0, first.accX, 0.0)
        assertEquals(-31.0, first.accY, 0.0)
        assertEquals(-287.0, first.accZ, 0.0)
        assertEquals(54775.0, first.gyroX, 0.0)
        assertEquals(-207.0, first.magX, 0.0)
        assertEquals(1010.74, first.pressureMb, 1e-9)
        assertEquals(28.42, first.temperatureC, 1e-9)
    }

    @Test
    fun parsesLegacyTimeColumn() {
        val rows = resource("Sens_legacy.csv").use(CsvParsers::parseSensorStream)
        assertEquals(2, rows.size)
        assertEquals(50.0, rows[0].ticks, 0.0)
        assertEquals(1024.0, rows[0].accX, 0.0)
    }

    @Test
    fun rejectsMissingColumn() {
        val malformed = """
            Time [10ms], AccX [mg]
            1, 100
        """.trimIndent().toByteArray()
        assertThrows(IOException::class.java) {
            ByteArrayInputStream(malformed).use(CsvParsers::parseSensorStream)
        }
    }

    @Test
    fun reportsBadRowWithLineNumber() {
        val malformed = """
            Time [10ms], AccX [mg], AccY [mg], AccZ [mg], GyroX [mdps], GyroY [mdps], GyroZ [mdps],MagX [mgauss],MagY [mgauss],MagZ [mgauss],P [mB],T ['C]
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
            2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, nope
        """.trimIndent().toByteArray()
        val ex = assertThrows(IOException::class.java) {
            ByteArrayInputStream(malformed).use(CsvParsers::parseSensorStream)
        }
        assertTrue("row number in message: ${ex.message}", ex.message!!.contains("row 3"))
    }

    // ---- GPS -------------------------------------------------------------

    @Test
    fun parsesGpsSample() {
        val rows = resource("Gps_sample.csv").use(CsvParsers::parseGpsStream)
        assertEquals(3, rows.size)
        val first = rows.first()
        assertEquals(101.0, first.ticks, 0.0)
        assertEquals("125053.90", first.utc)
        assertEquals(37.3829493, first.lat, 1e-9)
        assertEquals(23.2481843, first.lon, 1e-9)
        assertEquals(-0.30, first.altM, 1e-9)
        assertEquals(0.29, first.speedKmhModule, 1e-9)
        assertEquals(1, first.fix)
        assertEquals(12, first.numSat)
        assertEquals(0.60, first.hdop, 1e-9)
    }

    @Test
    fun parsesCompactGpsSchema() {
        // Post-22.4.2026 firmware: compact names, `ms` in raw milliseconds.
        val csv = """
            ms,utc,lat,lon,alt_m,speed_kmh,course_deg,fix_q,nsat,hdop
            1010,125053.90,37.3829493,23.2481843,-0.30,0.29,231.6,1,12,0.60
            # SYNC epoch_ms=1751882000000 tick_ms=1100
            1110,125054.00,37.3829500,23.2481850,-0.28,0.31,231.8,1,12,0.60
        """.trimIndent().toByteArray()
        val rows = ByteArrayInputStream(csv).use(CsvParsers::parseGpsStream)
        assertEquals(2, rows.size)
        assertEquals(101.0, rows[0].ticks, 0.0) // ms ÷ 10 → 10ms ticks
        assertEquals(37.3829493, rows[0].lat, 1e-9)
        assertEquals(0.31, rows[1].speedKmhModule, 1e-9)
    }

    @Test
    fun parsesBracketedUbloxGpsSchema() {
        // USB u-blox logger (`UbloxGpsCore`) + iOS watch logger schema.
        // A row missing lat/lon entirely is skipped; a GGA-only half row
        // (blank speed/course) is KEPT with NaN in the optional fields.
        val csv = """
            Time [10ms],UTC,Lat [deg],Lon [deg],Alt [m],SpeedKMh,Course [deg],Fix,NumSat,HDOP
            100,142025.00,47.372400,8.541700,410.100000,12.300000,88.000000,1,9,1.100000
            200,142026.00,,,,,,0,0,
            300,142026.00,47.372500,8.541900,410.200000,,,1,9,1.100000
        """.trimIndent().toByteArray()
        val rows = ByteArrayInputStream(csv).use(CsvParsers::parseGpsStream)
        assertEquals(2, rows.size)
        assertEquals(47.3724, rows[0].lat, 1e-9)
        assertEquals(12.3, rows[0].speedKmhModule, 1e-9)
        assertEquals(300.0, rows[1].ticks, 0.0)
        assertTrue(rows[1].speedKmhModule.isNaN())
        assertEquals(1, rows[1].fix)
    }

    @Test
    fun gpsSkipsTornTailRow() {
        val csv = """
            Time [10ms],UTC,Lat,Lon,Alt [m],Speed [km/h],Course [deg],Fix,NumSat,HDOP
            101,125053.90,37.3829493,23.2481843,-0.30,0.29,231.6,1,12,0.60
            201,125054.90,37.38
        """.trimIndent().toByteArray()
        val rows = ByteArrayInputStream(csv).use(CsvParsers::parseGpsStream)
        assertEquals(1, rows.size)
    }

    // ---- Battery ---------------------------------------------------------

    @Test
    fun parsesBatterySample() {
        val rows = resource("Bat_sample.csv").use(CsvParsers::parseBatteryStream)
        assertEquals(4, rows.size)
        assertEquals(110.0, rows[0].ticks, 0.0)
        assertEquals(4145, rows[0].voltageMv)
        assertEquals(100, rows[0].socTenthPct)
        assertEquals(0, rows[0].currentHundredUa)
        assertEquals(-73, rows[3].currentHundredUa)
    }

    @Test
    fun emptyFileThrows() {
        assertThrows(IOException::class.java) {
            ByteArrayInputStream(ByteArray(0)).use(CsvParsers::parseSensorStream)
        }
    }

    @Test
    fun blankRowsAreSkipped() {
        val csv = """
            Time [10ms], Voltage [mV], SOC [0.1%], Current [100uA]
            110, 4145, 100, 0

            210, 4145, 100, 0
        """.trimIndent().toByteArray()
        val rows = ByteArrayInputStream(csv).use(CsvParsers::parseBatteryStream)
        assertEquals(2, rows.size)
    }

    @Test
    fun loadsNonEmpty() {
        // Smoke test: classpath resources load without throwing.
        assertNotNull(resource("Sens_sample.csv"))
        assertNotNull(resource("Gps_sample.csv"))
        assertNotNull(resource("Bat_sample.csv"))
    }
}

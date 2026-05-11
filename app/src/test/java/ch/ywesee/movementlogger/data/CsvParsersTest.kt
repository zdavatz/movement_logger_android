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

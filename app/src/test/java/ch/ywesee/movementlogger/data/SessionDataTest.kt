package ch.ywesee.movementlogger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.TimeZone

class SessionDataTest {

    // ---- decimation -------------------------------------------------------

    @Test
    fun decimateKeepsExtremesAndBoundsSize() {
        val t = DoubleArray(100_000) { it * 0.01 }
        val y = FloatArray(t.size)
        y[54_321] = 9f
        y[77_777] = -4f
        val pts = SessionLoader.decimate(t, y, 0.0, 1000.0)
        assertTrue(pts.size / 2 <= SessionLoader.PLOT_BUCKETS * 2)
        val ys = (1 until pts.size step 2).map { pts[it] }
        assertTrue(9f in ys)
        assertTrue(-4f in ys)
        // x stays sorted (min/max emitted in time order inside each bucket)
        val xs = (0 until pts.size step 2).map { pts[it] }
        assertEquals(xs.sorted(), xs)
    }

    @Test
    fun decimateShortRangeIsRawAndSkipsNan() {
        val t = DoubleArray(10) { it.toDouble() }
        val y = FloatArray(10) { if (it == 4) Float.NaN else it.toFloat() }
        val pts = SessionLoader.decimate(t, y, 2.0, 6.0)
        // [lo, hi] plus one sample either side, NaN dropped: 1,2,3,5,6,7
        assertEquals(listOf(1f, 2f, 3f, 5f, 6f, 7f), (1 until pts.size step 2).map { pts[it] })
    }

    // ---- angle / height math ---------------------------------------------

    private fun arrays(rows: List<FloatArray>): SessionLoader.SensorArrays {
        val n = rows.size
        return SessionLoader.SensorArrays(
            ticks = DoubleArray(n) { it.toDouble() },
            ax = FloatArray(n) { rows[it][0] }, ay = FloatArray(n) { rows[it][1] }, az = FloatArray(n) { rows[it][2] },
            gyro = FloatArray(n), pressure = FloatArray(n) { 1000f }, n = n, anchors = emptyList(),
        )
    }

    @Test
    fun pitchAndRollUseBoxFrameNoseY() {
        // Nose (+Y) tilted up 30°: gravity reads on +Y and +Z.
        val s30 = 1000f * kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        val c30 = 1000f * kotlin.math.cos(Math.toRadians(30.0)).toFloat()
        val up = SessionLoader.build("s", null, arrays(List(200) { floatArrayOf(0f, s30, c30) }), emptyList())
        assertEquals(30f, up.pitchDeg[100], 0.01f)
        assertEquals(0f, up.rollDeg[100], 0.01f)
        // Leaning onto the +X side: roll, not pitch.
        val lean = SessionLoader.build("s", null, arrays(List(200) { floatArrayOf(s30, 0f, c30) }), emptyList())
        assertEquals(0f, lean.pitchDeg[100], 0.01f)
        assertEquals(30f, lean.rollDeg[100], 0.01f)
        assertEquals(1f, lean.accG[100], 1e-4f)
    }

    @Test
    fun smoothIsCenteredAverage() {
        val out = SessionLoader.smooth(floatArrayOf(0f, 3f, 6f), 3, 3)
        assertEquals(listOf(1.5f, 3f, 4.5f), out.toList())
    }

    @Test
    fun baroHeightFormula() {
        assertEquals(0.0, SessionLoader.baroHeightM(1013.25, 1013.25), 1e-9)
        // ~1 hPa ≈ 8.4 m near sea level
        assertEquals(8.4, SessionLoader.baroHeightM(1012.25, 1013.25), 0.1)
    }

    // ---- UTC / labels / naming -------------------------------------------

    @Test
    fun utcParsesFirmwareFormat() {
        assertEquals(16 * 3600 + 14 * 60 + 59.2, GpsTime.parseHhmmssSs("161459.20")!!, 1e-9)
        assertNull(GpsTime.parseHhmmssSs(""))
        assertNull(GpsTime.parseHhmmssSs("256000.00"))
    }

    @Test
    fun hmsWrapsAndFormats() {
        assertEquals("17:14:59", hms(17 * 3600 + 14 * 60 + 59.7, false))
        assertEquals("17:14:59.7", hms(17 * 3600 + 14 * 60 + 59.75, true))
        assertEquals("00:00:01", hms(86_401.0, false))
        assertEquals("23:59:59", hms(-1.0, false))
    }

    @Test
    fun matchesGpsFileBySensorNumber() {
        val c = listOf("GPS000.CSV", "GPS001.CSV", "Gps002.csv", "GpsPhone_20260728.csv", "BAT001.CSV")
        assertEquals("GPS001.CSV", SessionLoader.matchingGpsName("SENS001.CSV", c))
        assertEquals("Gps002.csv", SessionLoader.matchingGpsName("Sens002.csv", c))
        assertEquals("GpsPhone_20260728.csv", SessionLoader.matchingGpsName("SensPhone_20260728.csv", c))
        assertNull(SessionLoader.matchingGpsName("SENS009.CSV", c))
        assertNull(SessionLoader.matchingGpsName("BAT001.CSV", c))
    }

    @Test
    fun parsesCompactSensorSchemaWithSyncAnchor() {
        val csv = """
            ms,ax_mg,ay_mg,az_mg,gx_mdps,gy_mdps,gz_mdps,mx_mg,my_mg,mz_mg,p_hPa,t_C
            207834,-11,-11,999,-280,-210,-70,-786,867,-870,961.7,26.6
            # SYNC epoch_ms=1793117729388 tick_ms=207840
            207850,-11,-12,1001,-350,-210,0,-781,873,-865,961.8,26.6
            207860,-11,-12,1001,-350,-2
        """.trimIndent().toByteArray()
        val s = ByteArrayInputStream(csv).use(SessionLoader::parseSensor)
        assertEquals(2, s.n) // torn tail skipped
        assertEquals(20783.4, s.ticks[0], 1e-9)
        assertEquals(999f, s.az[0])
        assertEquals(961.8f, s.pressure[1])
        assertEquals(listOf(SyncAnchor(20784.0, 1793117729388L)), s.anchors)
        // The strict row parser reads the same schema (Replay path).
        val rows = ByteArrayInputStream(csv.decodeToString().substringBeforeLast("\n").toByteArray())
            .use(CsvParsers::parseSensorStream)
        assertEquals(2, rows.size)
        assertEquals(20785.0, rows[1].ticks, 1e-9)
        assertEquals(-12.0, rows[1].accY, 0.0)
    }

    @Test
    fun fieldParserMatchesParseDouble() {
        for (s in listOf("0", "-11", "961.7", "+3.25", " 42.000 ", "1e3", "-0.0001", "123456789.123456789")) {
            assertEquals(s, s.trim().toDouble(), parseFieldOrNan(s, 0, s.length), 0.0)
        }
        assertTrue(parseFieldOrNan("", 0, 0).isNaN())
        assertTrue(parseFieldOrNan("abc", 0, 3).isNaN())
        assertTrue(parseFieldOrNan("-", 0, 1).isNaN())
    }

    // ---- real session -----------------------------------------------------

    /**
     * Real 2 h 50 min box session (10 Hz GPS). Not checked in (57 MB) —
     * point `SESSION_DIR` at a folder with `SENS000.CSV` + `GPS000.CSV`:
     * `SESSION_DIR=… ./gradlew :app:testDebugUnitTest --tests '*SessionDataTest*'`.
     * Expected values are the desktop Session tab's on the same files.
     */
    @Test
    fun loadsRealSession() {
        val dir = System.getenv("SESSION_DIR")?.let(::File)
        assumeTrue("SESSION_DIR not set", dir != null && File(dir, "SENS000.CSV").isFile)
        val t = System.nanoTime()
        val d = SessionLoader.load(File(dir, "SENS000.CSV"), File(dir!!, "GPS000.CSV"))
        val ms = (System.nanoTime() - t) / 1_000_000
        val zurich = TimeZone.getTimeZone("Europe/Zurich")
        println(
            "loaded in $ms ms: ${d.sensT.size} sens rows, ${d.gpsT.size} gps rows, ${d.track.size} track pts, " +
                "%.0f s, %.2f km, max %.1f km/h, GPS %.1f Hz, start %s, clock %s".format(
                    d.durationS, d.distanceM / 1000, d.maxSpeedKmh, d.gpsRateHz, d.label(0.0, false, zurich),
                    d.clock::class.simpleName,
                ),
        )
        println(d.readout(d.durationS / 2, zurich))
        assertTrue(d.sensT.size > 950_000)
        assertTrue(d.gpsT.size > 20_000)
        assertEquals(5.30, d.distanceM / 1000, 0.005)
        assertEquals(35.0, d.maxSpeedKmh, 0.05)
        assertEquals(10.0, d.gpsRateHz, 0.01)
        assertEquals("17:14:59", d.label(0.0, false, zurich))
        assertTrue(d.clock is SessionClock.Anchors)
        assertTrue(d.durationS in 10_000.0..10_400.0)
        // Decimated full view stays bounded.
        assertTrue(SessionLoader.decimate(d.sensT, d.accG, 0.0, d.durationS).size <= SessionLoader.PLOT_BUCKETS * 4)
    }
}

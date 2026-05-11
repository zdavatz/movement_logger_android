package ch.ywesee.movementlogger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMathTest {

    private fun row(ticks: Double, lat: Double, lon: Double) = GpsRow(
        ticks = ticks, utc = "000000.00",
        lat = lat, lon = lon, altM = 0.0,
        speedKmhModule = 0.0, courseDeg = 0.0,
        fix = 1, numSat = 8, hdop = 1.0,
    )

    @Test
    fun haversineEquatorEastDegree() {
        // One degree at the equator ≈ 111.195 km using the spherical model
        val d = GpsMath.haversineM(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, d, 200.0)
    }

    @Test
    fun haversineSamePointIsZero() {
        val d = GpsMath.haversineM(47.37, 8.54, 47.37, 8.54)
        assertEquals(0.0, d, 1e-9)
    }

    @Test
    fun positionSpeedFirstRowIsZero() {
        // Move ~111 m east over 1 second → ~400 km/h, but row 0 stays 0.
        val pts = listOf(
            row(100.0, 0.0, 0.0),
            row(200.0, 0.0, 0.001),
        )
        val v = GpsMath.positionDerivedSpeedKmh(pts)
        assertEquals(0.0, v[0], 0.0)
        assertEquals(400.0, v[1], 5.0)  // ~111.195 m * 3.6 = ~400 km/h
    }

    @Test
    fun rejectsAccelerationSpikes() {
        // Three rows, 1 s apart. Speeds: 5 → 5 → 35 km/h. Last one is a 30 km/h
        // jump in 1 s — way above 15 km/h/s threshold AND v > 15.
        val pts = listOf(row(100.0, 0.0, 0.0), row(200.0, 0.0, 0.0), row(300.0, 0.0, 0.0))
        val raw = doubleArrayOf(5.0, 5.0, 35.0)
        val out = GpsMath.rejectAccOutliers(pts, raw, maxAccelKmhPerS = 15.0)
        assertEquals(5.0, out[0], 0.0)
        assertEquals(5.0, out[1], 0.0)
        assertTrue("expected NaN spike, got ${out[2]}", out[2].isNaN())
    }

    @Test
    fun smoothClipsAndMedians() {
        val raw = doubleArrayOf(
            5.0, 5.0, 5.0, 80.0 /* clipped */, 5.0, 5.0, 5.0, 5.0, 5.0,
        )
        val out = GpsMath.smoothSpeedKmh(raw)
        assertEquals(out.size, raw.size)
        // The 80 km/h glitch should be interpolated/median'd back near 5
        assertTrue("expected glitch absorbed, got ${out[3]}", out[3] < 10.0)
    }

    @Test
    fun linearInterpolateFillsHoles() {
        val a = doubleArrayOf(1.0, Double.NaN, Double.NaN, 4.0)
        GpsMath.linearInterpolateInPlace(a)
        assertEquals(1.0, a[0], 0.0)
        assertEquals(2.0, a[1], 1e-9)
        assertEquals(3.0, a[2], 1e-9)
        assertEquals(4.0, a[3], 0.0)
    }

    @Test
    fun linearInterpolateBackfillsLeadingNaN() {
        val a = doubleArrayOf(Double.NaN, Double.NaN, 7.0, 9.0)
        GpsMath.linearInterpolateInPlace(a)
        assertEquals(7.0, a[0], 0.0)
        assertEquals(7.0, a[1], 0.0)
    }

    @Test
    fun linearInterpolateAllNanBecomesZero() {
        val a = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
        GpsMath.linearInterpolateInPlace(a)
        assertEquals(0.0, a[0], 0.0)
        assertEquals(0.0, a[1], 0.0)
        assertEquals(0.0, a[2], 0.0)
    }

    @Test
    fun rollingMedianPicksMiddle() {
        val a = doubleArrayOf(1.0, 100.0, 2.0, 3.0, 4.0)
        val out = GpsMath.rollingMedian(a, 5)
        // Centred 5-window: at index 2 the buffer covers indices [0..4] sorted
        // = [1,2,3,4,100]; the median is buf[len/2] = buf[2] = 3.
        assertEquals(3.0, out[2], 0.0)
    }

    @Test
    fun fastAndSimpleMedianAgree() {
        // Random-ish data, large enough to exercise the fast path.
        val n = 1024
        val seed = 7L
        val rng = java.util.Random(seed)
        val a = DoubleArray(n) { rng.nextDouble() * 100.0 - 50.0 }
        val w = 51  // odd, > 32 → fast path
        val fast = GpsMath.rollingMedianFast(a, w)
        val slow = GpsMath.rollingMedianSimple(a, w)
        for (i in 0 until n) {
            assertEquals("idx $i", slow[i], fast[i], 1e-12)
        }
    }

    @Test
    fun fastMedianHandlesEvenWindow() {
        val n = 512
        val rng = java.util.Random(11L)
        val a = DoubleArray(n) { rng.nextDouble() * 10.0 }
        val w = 64  // even, > 32
        val fast = GpsMath.rollingMedianFast(a, w)
        val slow = GpsMath.rollingMedianSimple(a, w)
        for (i in 0 until n) {
            assertEquals("idx $i", slow[i], fast[i], 1e-12)
        }
    }
}

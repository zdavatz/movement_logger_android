package ch.ywesee.movementlogger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-math coverage for the ride-map renderer (no android.* involved). */
class RideMapRendererTest {

    @Test
    fun mercatorRoundNumbers() {
        // Zoom 0: the world is one 256 px tile; (0,0) sits dead centre.
        assertEquals(128.0, RideMapRenderer.lonToPx(0.0, 0), 1e-9)
        assertEquals(128.0, RideMapRenderer.latToPx(0.0, 0), 1e-9)
        assertEquals(0.0, RideMapRenderer.lonToPx(-180.0, 0), 1e-9)
        assertEquals(256.0, RideMapRenderer.lonToPx(180.0, 0), 1e-9)
        // North is up: bigger latitude → smaller pixel Y.
        assertTrue(RideMapRenderer.latToPx(47.0, 10) < RideMapRenderer.latToPx(46.0, 10))
    }

    @Test
    fun fitZoomFitsAndIsMaximal() {
        // ~1 km track near Zürich.
        val bbox = RideMapRenderer.boundingBox(
            doubleArrayOf(47.3660, 47.3760), doubleArrayOf(8.5400, 8.5500),
        )!!
        val z = RideMapRenderer.fitZoom(bbox, 1080, 1440)
        fun spanPx(zoom: Int): Pair<Double, Double> = Pair(
            RideMapRenderer.lonToPx(bbox[3], zoom) - RideMapRenderer.lonToPx(bbox[2], zoom),
            RideMapRenderer.latToPx(bbox[0], zoom) - RideMapRenderer.latToPx(bbox[1], zoom),
        )
        val (w, h) = spanPx(z)
        assertTrue("fits at z=$z ($w×$h)", w <= 1080 && h <= 1440)
        val (w1, h1) = spanPx(z + 1)
        assertTrue("z+1 must overflow ($w1×$h1)", w1 > 1080 || h1 > 1440)
    }

    @Test
    fun stationarySessionGetsMinimumSpan() {
        val bbox = RideMapRenderer.boundingBox(doubleArrayOf(47.37), doubleArrayOf(8.54))!!
        assertTrue(bbox[1] - bbox[0] >= 0.0005)
        assertTrue(bbox[3] - bbox[2] >= 0.0005)
        // Degenerate bbox must not push fitZoom past the tile servers' max.
        assertTrue(RideMapRenderer.fitZoom(bbox, 1080, 1440) <= 19)
    }

    @Test
    fun downsampleKeepsEndsAndBound() {
        val idx = RideMapRenderer.downsampleIndices(10_000, 2000)
        assertTrue(idx.size <= 2001)
        assertEquals(0, idx.first())
        assertEquals(9999, idx.last())
        // Short input passes through untouched.
        assertEquals(5, RideMapRenderer.downsampleIndices(5, 2000).size)
    }

    @Test
    fun robustMaxIgnoresSpike() {
        val speeds = DoubleArray(100) { 10.0 }
        speeds[50] = 300.0 // single GPS glitch
        assertEquals(10.0, RideMapRenderer.robustMaxSpeed(speeds), 1e-9)
    }

    @Test
    fun speedHueBlueToRed() {
        assertEquals(0.66, RideMapRenderer.speedHue(0.0, 30.0), 1e-9)
        assertEquals(0.0, RideMapRenderer.speedHue(30.0, 30.0), 1e-9)
        assertEquals(0.0, RideMapRenderer.speedHue(99.0, 30.0), 1e-9) // clamped
        assertEquals(0.33, RideMapRenderer.speedHue(15.0, 30.0), 1e-9)
    }

    @Test
    fun fillSpeedGapsForwardAndBackFills() {
        val filled = RideMapRenderer.fillSpeedGaps(
            doubleArrayOf(Double.NaN, 5.0, Double.NaN, Double.NaN, 7.0, Double.NaN),
        )
        org.junit.Assert.assertArrayEquals(
            doubleArrayOf(5.0, 5.0, 5.0, 5.0, 7.0, 7.0), filled, 1e-9,
        )
        org.junit.Assert.assertArrayEquals(
            doubleArrayOf(0.0, 0.0),
            RideMapRenderer.fillSpeedGaps(doubleArrayOf(Double.NaN, Double.NaN)), 1e-9,
        )
    }

    @Test
    fun validPointsDropsNullIslandAndNoFix() {
        fun row(lat: Double, lon: Double, fix: Int) = GpsRow(
            ticks = 0.0, utc = "", lat = lat, lon = lon, altM = 0.0,
            speedKmhModule = 0.0, courseDeg = 0.0, fix = fix, numSat = 0, hdop = 0.0,
        )
        val pts = RideMapRenderer.validPoints(
            listOf(
                row(47.0, 8.0, 1),
                row(0.0, 0.0, 1),          // null island
                row(47.0, 8.0, 0),          // no fix
                row(Double.NaN, 8.0, 1),    // NaN
                row(47.1, 8.1, 2),
                // Flagged-garbage accuracy: the 11.7.2026 watch ride carried
                // a WiFi-fallback fix 70 km away at accuracy 149 000 m.
                GpsRow(
                    ticks = 0.0, utc = "", lat = 38.0, lon = 23.3, altM = 0.0,
                    speedKmhModule = 0.0, courseDeg = 0.0, fix = 1, numSat = 0,
                    hdop = 149_000.0,
                ),
            )
        )
        assertEquals(2, pts.size)
    }

    @Test
    fun cleanTrackSegmentsDropsFabricationAndBreaksAtHoles() {
        val mPerDegLon = Math.PI * 6_371_000.0 / 180.0
        fun fixRow(ticks: Double, meters: Double, utc: String = ticks.toString()) = GpsRow(
            ticks = ticks, utc = utc, lat = 0.0, lon = meters / mPerDegLon, altM = 0.0,
            speedKmhModule = Double.NaN, courseDeg = 0.0, fix = 2, numSat = 8, hdop = 1.0,
        )
        val rows = mutableListOf<GpsRow>()
        var meters = 100.0   // away from (0,0) so the null-island filter stays out of the way
        for (t in 0..2500 step 10) { rows.add(fixRow(t.toDouble(), meters)); meters += 6.0 / 3.6 * 0.1 }
        // Fabricated 27 km/h slide right before the blackout.
        for (t in 2510..3000 step 10) { rows.add(fixRow(t.toDouble(), meters)); meters += 27.0 / 3.6 * 0.1 }
        // Blackout 3000..3500, then honest cruise resumes.
        for (t in 3500..7000 step 10) { rows.add(fixRow(t.toDouble(), meters)); meters += 6.0 / 3.6 * 0.1 }

        val segs = RideMapRenderer.cleanTrackSegments(rows)
        assertEquals(2, segs.size)
        // Leading 10 s convergence window is dropped…
        assertEquals(1010.0, segs[0].first().ticks, 1e-9)
        // …the fabricated slide and both blackout pads are gone…
        assertEquals(1990.0, segs[0].last().ticks, 1e-9)
        assertEquals(4510.0, segs[1].first().ticks, 1e-9)
        // …and distance never bridges the hole between the segments.
        val distM = RideMapRenderer.segmentsDistanceKm(segs) * 1000.0
        val expected = (1990 - 1010 + 7000 - 4510) / 100.0 * (6.0 / 3.6)
        assertEquals(expected, distM, 1.0)
    }

    @Test
    fun stalledDuplicateRowsBecomeABlackout() {
        val mPerDegLon = Math.PI * 6_371_000.0 / 180.0
        fun fixRow(ticks: Double, meters: Double, utc: String) = GpsRow(
            ticks = ticks, utc = utc, lat = 0.0, lon = meters / mPerDegLon, altM = 0.0,
            speedKmhModule = 9.4, courseDeg = 0.0, fix = 1, numSat = 0, hdop = 10.0,
        )
        val rows = mutableListOf<GpsRow>()
        var meters = 100.0
        for (t in 0..3000 step 100) { rows.add(fixRow(t.toDouble(), meters, t.toString())); meters += 6.0 / 3.6 }
        // Watch-logger stall: the same last-known row rewritten every second
        // (frozen UTC + position + speed) — must NOT read as a live fix
        // timeline, and the frozen 9.4 km/h must not become the top speed.
        for (t in 3100..7000 step 100) rows.add(fixRow(t.toDouble(), meters, "STALL"))
        for (t in 7100..12000 step 100) { rows.add(fixRow(t.toDouble(), meters, t.toString())); meters += 2.0 / 3.6 }

        val segs = RideMapRenderer.cleanTrackSegments(rows)
        assertEquals(2, segs.size)
        // The stall duplicates collapse to one fix, opening a ≥2 s hole —
        // nothing from the stall window survives in either segment.
        assertTrue(segs[0].last().ticks < 3100.0)
        assertTrue(segs[1].first().ticks > 7000.0)
    }

    @Test
    fun smoothKeysAbsorbsShortRunsIntoLongerNeighbour() {
        // A 1-sample flicker and a 10 s run both sit under the 20 s floor —
        // both collapse into the sustained run around them.
        val ticks = doubleArrayOf(0.0, 1000.0, 2000.0, 2100.0, 3000.0, 4000.0)
        val keys = intArrayOf(0, 0, 0, 1, 1, 1)
        val out = RideMapRenderer.smoothKeys(keys, ticks, 20.0)
        assertTrue(out.all { it == 0 })
        // Sustained runs (≥ 20 s each) stay untouched.
        val ticks2 = doubleArrayOf(0.0, 2000.0, 4500.0, 5500.0, 7000.0)
        val keys2 = intArrayOf(0, 0, 1, 1, 1)
        org.junit.Assert.assertArrayEquals(keys2, RideMapRenderer.smoothKeys(keys2, ticks2, 20.0))
    }

    @Test
    fun rideModesClassifyWalkRideSwim() {
        // Synthetic session along the equator: walk to the water, ride,
        // fall in (blackout holes every ~30 s while swimming — the antenna
        // rides at the surface), remount, ride home. Speeds sit on the fix
        // rows box-CSV-style.
        val mPerDegLon = Math.PI * 6_371_000.0 / 180.0
        fun row(ticks: Double, meters: Double, v: Double) = GpsRow(
            ticks = ticks, utc = ticks.toString(), lat = 0.0, lon = meters / mPerDegLon,
            altM = 0.0, speedKmhModule = v, courseDeg = 0.0, fix = 2, numSat = 8, hdop = 1.0,
        )
        val rows = mutableListOf<GpsRow>()
        var meters = 100.0
        fun phase(fromT: Int, toT: Int, v: Double) {
            var t = fromT
            while (t <= toT) { rows.add(row(t.toDouble(), meters, v)); meters += v / 3.6 * 0.1; t += 10 }
        }
        phase(0, 6000, 4.0)        // walk on land
        phase(6010, 19990, 15.0)   // on board
        // fall in at 20000: holes 20000-21000, 24000-25000, 28000-29000
        phase(21000, 23990, 1.5)   // swimming between the holes
        phase(25000, 27990, 1.5)
        phase(29000, 32000, 1.5)
        phase(32010, 45000, 15.0)  // remounted, riding home

        val pts = RideMapRenderer.cleanTrackSegments(rows).flatten()
        // No water mask (offline fallback): fast → board, near-hole → swim,
        // else land.
        val modes = RideMapRenderer.rideModes(rows, pts, null)
        assertEquals(pts.size, modes.size)
        for ((i, p) in pts.withIndex()) {
            val expect = when {
                p.ticks <= 6000.0 -> RideMode.LAND
                p.ticks <= 19990.0 -> RideMode.BOARD
                p.ticks <= 32000.0 -> RideMode.SWIM
                else -> RideMode.BOARD
            }
            assertEquals("tick ${p.ticks}", expect, modes[i])
        }
        // All three modes actually appear (guards the test itself).
        assertEquals(RideMode.entries.toSet(), modes.toSet())
    }

    @Test
    fun rideModesWithWaterMaskSplitDriftFromRideAndLand() {
        // Geography decides land vs water; on water, speed decides swim vs
        // board — this is where the mask beats the fallback: a clean slow
        // drift is IN the water (fallback would call it land), and slow
        // para-wing riding at 4.5 km/h is on the board (fallback needs 6).
        val mPerDegLon = Math.PI * 6_371_000.0 / 180.0
        fun row(ticks: Double, meters: Double, v: Double) = GpsRow(
            ticks = ticks, utc = ticks.toString(), lat = 0.0, lon = meters / mPerDegLon,
            altM = 0.0, speedKmhModule = v, courseDeg = 0.0, fix = 2, numSat = 8, hdop = 1.0,
        )
        val rows = mutableListOf<GpsRow>()
        var meters = 100.0
        fun phase(fromT: Int, toT: Int, v: Double) {
            var t = fromT
            while (t <= toT) { rows.add(row(t.toDouble(), meters, v)); meters += v / 3.6 * 0.1; t += 10 }
        }
        phase(0, 6000, 4.0)        // walking on the quay — 4 km/h but LAND
        phase(6010, 12000, 2.0)    // drifting in the water, clean fixes
        phase(12010, 20000, 4.5)   // slow para-wing riding

        val pts = RideMapRenderer.cleanTrackSegments(rows).flatten()
        val mask = BooleanArray(pts.size) { pts[it].ticks > 6000.0 }
        val modes = RideMapRenderer.rideModes(rows, pts, mask)
        for ((i, p) in pts.withIndex()) {
            val expect = when {
                p.ticks <= 6000.0 -> RideMode.LAND
                p.ticks <= 12000.0 -> RideMode.SWIM
                else -> RideMode.BOARD
            }
            assertEquals("tick ${p.ticks}", expect, modes[i])
        }
    }

    @Test
    fun waterColorMatchesOsmCartoWithTolerance() {
        assertTrue(RideMapRenderer.isWaterColor(0xFFAAD3DF.toInt()))   // exact
        assertTrue(RideMapRenderer.isWaterColor(0xFFA0D3DF.toInt()))   // −10 red
        assertTrue(!RideMapRenderer.isWaterColor(0xFF9FD3DF.toInt()))  // −11 red
        assertTrue(!RideMapRenderer.isWaterColor(0xFFD2C7BD.toInt()))  // Ermioni quay
        assertTrue(!RideMapRenderer.isWaterColor(0xFFF2EFE9.toInt()))  // OSM land fill
    }

    @Test
    fun robustTopSpeedSurvivesUnderwaterFabricationAndBlips() {
        // Track along the equator so metres → degrees is trivial.
        val mPerDegLon = Math.PI * 6_371_000.0 / 180.0
        fun fixRow(ticks: Double, meters: Double) = GpsRow(
            ticks = ticks, utc = "", lat = 0.0, lon = meters / mPerDegLon, altM = 0.0,
            speedKmhModule = Double.NaN, courseDeg = 0.0, fix = 2, numSat = 8, hdop = 1.0,
        )
        fun spdRow(ticks: Double, v: Double) = GpsRow(
            ticks = ticks, utc = "", lat = 0.0, lon = 0.0, altM = Double.NaN,
            speedKmhModule = v, courseDeg = Double.NaN, fix = 0, numSat = 0, hdop = Double.NaN,
        )
        val rows = mutableListOf<GpsRow>()
        var meters = 0.0
        // Honest 6 km/h cruise, fixes at 10 Hz, ticks 0..2500.
        for (t in 0..2500 step 10) { rows.add(fixRow(t.toDouble(), meters)); meters += 6.0 / 3.6 * 0.1 }
        // Antenna sinking: the filter fabricates a self-consistent 27 km/h
        // slide (positions AND speed agree) right before the signal dies.
        for (t in 2510..3000 step 10) { rows.add(fixRow(t.toDouble(), meters)); meters += 27.0 / 3.6 * 0.1 }
        // Blackout: no fixes ticks 3000..3500. Then honest cruise resumes.
        for (t in 3500..7000 step 10) { rows.add(fixRow(t.toDouble(), meters)); meters += 6.0 / 3.6 * 0.1 }

        rows.add(spdRow(1505.0, 7.0))   // honest peak mid-cruise → counts
        rows.add(spdRow(2805.0, 27.1))  // fabricated slide: position-consistent,
                                        // but blackout-adjacent → out
        rows.add(spdRow(5005.0, 6.5))   // honest, far from the blackout → counts
        rows.add(spdRow(5205.0, 80.0))  // above the 60 km/h hard clip → out
        rows.add(spdRow(5505.0, 25.0))  // isolated doppler blip amid a 6 km/h
                                        // cruise: chord check rejects → out
        rows.sortBy { it.ticks }
        assertEquals(7.0, RideMapRenderer.robustTopSpeed(rows), 1e-9)
    }
}

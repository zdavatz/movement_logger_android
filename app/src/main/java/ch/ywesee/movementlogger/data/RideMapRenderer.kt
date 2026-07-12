package ch.ywesee.movementlogger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Shareable ride-map PNG — Android port of the iOS `RideMapRenderer`
 * (`RideMap.swift`): real map tiles under an activity-coloured track
 * (swim / board / land, see [RideMode]), with the app logo, ride stats and
 * the GitHub source link baked into a branded footer. iOS snapshots Apple
 * Maps via `MKMapSnapshotter`; here we stitch
 * OpenStreetMap tiles ourselves (Web-Mercator math below), which keeps the
 * output identical in spirit and needs no API key.
 *
 * The pure geometry/statistics helpers are `internal` and JVM-only (no
 * android.* types) so `RideMapMathTest` can cover them.
 */
/**
 * Inferred activity for a stretch of the ride. Colours and labels are the
 * iOS `RideMode` values verbatim (swim 0.20/0.55/0.95, board 0.16/0.78/0.42,
 * land 0.95/0.55/0.13 in sRGB) so both apps' maps read identically.
 */
enum class RideMode(val label: String, val argb: Int) {
    SWIM("In water", 0xFF338CF2.toInt()),   // blue
    BOARD("On board", 0xFF29C76B.toInt()),  // green
    LAND("On land", 0xFFF28C21.toInt()),    // orange
}

object RideMapRenderer {
    const val SOURCE_URL = "github.com/zdavatz/movement_logger_android"

    private const val TILE_SIZE = 256
    private const val MAX_ZOOM = 19
    /** 18 % margin so the track isn't jammed against the frame (iOS parity). */
    private const val BBOX_MARGIN = 0.18
    /** Minimum bbox span in degrees (~55 m) so a near-stationary session
     *  doesn't produce a zoom-19 postage stamp of one metre of water. */
    private const val MIN_SPAN_DEG = 0.0005

    // ---- pure math (unit-tested) -----------------------------------------

    /** Lat/lon bounding box with margin, or null if empty. */
    internal fun boundingBox(lats: DoubleArray, lons: DoubleArray): DoubleArray? {
        if (lats.isEmpty()) return null
        var latMin = lats[0]; var latMax = lats[0]
        var lonMin = lons[0]; var lonMax = lons[0]
        for (i in lats.indices) {
            latMin = min(latMin, lats[i]); latMax = max(latMax, lats[i])
            lonMin = min(lonMin, lons[i]); lonMax = max(lonMax, lons[i])
        }
        var spanLat = max(latMax - latMin, MIN_SPAN_DEG)
        var spanLon = max(lonMax - lonMin, MIN_SPAN_DEG)
        val cLat = (latMin + latMax) / 2
        val cLon = (lonMin + lonMax) / 2
        spanLat *= 1 + 2 * BBOX_MARGIN
        spanLon *= 1 + 2 * BBOX_MARGIN
        return doubleArrayOf(cLat - spanLat / 2, cLat + spanLat / 2, cLon - spanLon / 2, cLon + spanLon / 2)
    }

    /** Longitude → global Web-Mercator pixel X at [zoom]. */
    internal fun lonToPx(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * TILE_SIZE * (1 shl zoom)

    /** Latitude → global Web-Mercator pixel Y at [zoom]. */
    internal fun latToPx(lat: Double, zoom: Int): Double {
        val rad = lat * PI / 180.0
        val merc = ln(tan(rad) + 1.0 / kotlin.math.cos(rad))
        return (1.0 - merc / PI) / 2.0 * TILE_SIZE * (1 shl zoom)
    }

    /**
     * Largest zoom ≤ [MAX_ZOOM] at which the bbox (latMin, latMax, lonMin,
     * lonMax) fits inside a widthPx × heightPx viewport.
     */
    internal fun fitZoom(bbox: DoubleArray, widthPx: Int, heightPx: Int): Int {
        val (latMin, latMax, lonMin, lonMax) = bbox
        val w0 = lonToPx(lonMax, 0) - lonToPx(lonMin, 0)
        val h0 = latToPx(latMin, 0) - latToPx(latMax, 0) // y grows south
        if (w0 <= 0 || h0 <= 0) return MAX_ZOOM
        val z = floor(min(log2(widthPx / w0), log2(heightPx / h0))).toInt()
        return z.coerceIn(1, MAX_ZOOM)
    }

    /**
     * Even-stride downsample indices so an interactive polyline (or a very
     * dense track) stays light without changing the visible shape.
     */
    internal fun downsampleIndices(count: Int, maxOut: Int): IntArray {
        if (count <= maxOut || maxOut <= 1) return IntArray(count) { it }
        val stride = count.toDouble() / maxOut
        val out = ArrayList<Int>(maxOut + 1)
        var i = 0.0
        while (i.toInt() < count) {
            out.add(i.toInt()); i += stride
        }
        if (out.last() != count - 1) out.add(count - 1)
        return out.toIntArray()
    }

    /** 95th-percentile speed so a single GPS spike doesn't wash the whole
     *  colour scale toward blue (iOS parity). */
    internal fun robustMaxSpeed(speeds: DoubleArray): Double {
        val s = speeds.filter { it.isFinite() && it >= 0 }.sorted()
        if (s.isEmpty()) return 0.0
        return s[((s.size - 1) * 0.95).toInt()]
    }

    /** Speed → hue in [0, 0.66]: blue (slow) → cyan → green → yellow → red. */
    internal fun speedHue(speed: Double, vMax: Double): Double {
        val t = (speed / vMax).coerceIn(0.0, 1.0)
        return (1 - t) * 0.66
    }

    /** Σ haversine hops over the track, km. */
    internal fun trackDistanceKm(lats: DoubleArray, lons: DoubleArray): Double {
        var m = 0.0
        for (i in 1 until lats.size) {
            m += GpsMath.haversineM(lats[i - 1], lons[i - 1], lats[i], lons[i])
        }
        return m / 1000.0
    }

    /** Positions with a worse claimed accuracy than this are flagged
     *  garbage: the 11.7.2026 watch ride carried one WiFi-database fallback
     *  fix 70 km away — honestly stamped with a 149 000 m accuracy. The
     *  u-blox HDOP is dimensionless (≤ ~20 with any usable fix) and the
     *  watch column carries metres, but one threshold covers both. NaN
     *  (column absent) passes. */
    internal const val MAX_PLAUSIBLE_HDOP = 50.0

    /** Keep only plottable fixes: fix>0, finite, not (0,0) null island,
     *  and not flagged wildly-inaccurate (see [MAX_PLAUSIBLE_HDOP]). */
    fun validPoints(rows: List<GpsRow>): List<GpsRow> = rows.filter {
        it.fix > 0 && it.lat.isFinite() && it.lon.isFinite() &&
            !(it.lat == 0.0 && it.lon == 0.0) && !(it.hdop > MAX_PLAUSIBLE_HDOP)
    }

    /** The watch logger writes the LAST KNOWN location once per second while
     *  no fresh fix arrives (42 identical rows during the 11.7.2026 stall),
     *  so a dead receiver still looks like a live fix timeline. Collapse
     *  consecutive identical fixes so a stall becomes a real hole that the
     *  blackout rule can see. u-blox epochs always advance UTC, so genuine
     *  stationary tracks are never collapsed. */
    private fun dedupFixes(fixes: List<GpsRow>): List<GpsRow> {
        val out = ArrayList<GpsRow>(fixes.size)
        for (f in fixes) {
            val p = out.lastOrNull()
            if (p != null && p.utc == f.utc && p.lat == f.lat && p.lon == f.lon) continue
            out.add(f)
        }
        return out
    }

    /** Same hard clip as `GpsMath.smoothSpeedKmh` — >60 km/h on a pumpfoil
     *  is always a bad fix. */
    internal const val MAX_PLAUSIBLE_SPEED_KMH = 60.0
    /** The fix pair backing the position cross-check must sit within ±1 s
     *  of the speed row (`Time [10ms]` ticks — box ms columns are ÷10 by
     *  the parser, so the unit holds across all CSV generations)… */
    private const val FIX_WINDOW_TICKS = 100.0
    /** …and span at least 0.5 s, so 10 Hz position jitter (±0.5 m) can't
     *  fake a huge derived speed. */
    private const val MIN_FIX_SPAN_TICKS = 50.0
    /** Reported speed may exceed the chord-derived speed by this factor
     *  (curved path inside the window, doppler vs. position lag)… */
    private const val SPEED_VS_TRACK_FACTOR = 3.0
    /** …plus this floor, so near-stationary jitter doesn't reject slow rows. */
    private const val SPEED_VS_TRACK_FLOOR_KMH = 5.0
    /** A hole ≥ this (2 s) in the valid-fix timeline is a signal blackout —
     *  on the water that means the antenna went under… */
    private const val BLACKOUT_GAP_TICKS = 200.0
    /** …and no speed row within this (10 s) of a blackout counts: while the
     *  antenna sinks, u-blox's filter fabricates a smooth, self-consistent
     *  speed ramp WITH matching sliding positions and healthy quality flags
     *  (12 sats / HDOP 0.6 claimed on the 11.7.2026 ride while the reported
     *  speed climbed 5 → 27 km/h on a ~1.5 km/h swimmer), so neither
     *  quality gates, nor an acceleration limit, nor the position
     *  cross-check below can catch it. Blackout adjacency is the one
     *  reliable signature — the fabrication episode always brackets the
     *  moment the signal actually died. */
    private const val BLACKOUT_PAD_TICKS = 1_000.0

    /**
     * Top speed for the footer stat, outlier-hardened. Three gates, each
     * physical and each verified against the 11.7.2026 Ermioni ride, whose
     * raw column peaked at a fantasy 27.1 km/h on a ~7 km/h session:
     *
     *  1. Hard clip at [MAX_PLAUSIBLE_SPEED_KMH].
     *  2. Blackout adjacency (see [BLACKOUT_PAD_TICKS]) — kills the
     *     antenna-under-water fabrication episodes.
     *  3. Position consistency: the earliest and latest valid fix within
     *     ±[FIX_WINDOW_TICKS] must span ≥ [MIN_FIX_SPAN_TICKS], and the row
     *     is accepted iff `v ≤ chordSpeed × [SPEED_VS_TRACK_FACTOR] +
     *     [SPEED_VS_TRACK_FLOOR_KMH]` — kills isolated doppler blips that
     *     aren't blackout-adjacent. Ordinary RMC half-rows (fix column 0,
     *     speed valid) pass because fixed GGA rows land within 0.1 s on
     *     both sides.
     *
     * `UbloxGpsCore.computeStats` delegates here so the footer and the
     * recordings-list stat stay equal.
     */
    internal fun robustTopSpeed(rows: List<GpsRow>): Double {
        val fixes = dedupFixes(validPoints(rows))
        if (fixes.size < 2) return 0.0
        val fixTicks = DoubleArray(fixes.size) { fixes[it].ticks }
        val zones = blackoutZones(fixTicks)

        var top = 0.0
        for (r in rows) {
            val v = r.speedKmhModule
            if (!v.isFinite() || v < 0.0 || v > MAX_PLAUSIBLE_SPEED_KMH) continue
            if (v <= top || !r.ticks.isFinite()) continue
            if (zones.any { r.ticks >= it[0] && r.ticks <= it[1] }) continue
            val a = lowerBound(fixTicks, r.ticks - FIX_WINDOW_TICKS)
            val b = lowerBound(fixTicks, r.ticks + FIX_WINDOW_TICKS + 1e-9) - 1
            if (b <= a) continue
            val span = fixTicks[b] - fixTicks[a]
            if (span < MIN_FIX_SPAN_TICKS) continue
            val chordKmh = GpsMath.haversineM(
                fixes[a].lat, fixes[a].lon, fixes[b].lat, fixes[b].lon,
            ) / (span / GpsMath.TICKS_PER_SEC) * 3.6
            if (v <= chordKmh * SPEED_VS_TRACK_FACTOR + SPEED_VS_TRACK_FLOOR_KMH) top = v
        }
        return top
    }

    /** Blackout exclusion zones as [start, end] tick pairs: before the
     *  first fix settles, around every ≥2 s hole in the fix timeline, and
     *  after the last fix. */
    private fun blackoutZones(fixTicks: DoubleArray): List<DoubleArray> {
        val zones = ArrayList<DoubleArray>()
        zones.add(doubleArrayOf(Double.NEGATIVE_INFINITY, fixTicks[0] + BLACKOUT_PAD_TICKS))
        for (i in 1 until fixTicks.size) {
            if (fixTicks[i] - fixTicks[i - 1] >= BLACKOUT_GAP_TICKS) {
                zones.add(
                    doubleArrayOf(
                        fixTicks[i - 1] - BLACKOUT_PAD_TICKS,
                        fixTicks[i] + BLACKOUT_PAD_TICKS,
                    )
                )
            }
        }
        zones.add(doubleArrayOf(fixTicks.last() + 1e-9, Double.POSITIVE_INFINITY))
        return zones
    }

    /**
     * The drawable track, cleaned with the same blackout rule as
     * [robustTopSpeed] and split into continuous segments: positions within
     * ±10 s of a ≥2 s fix hole are dropped (that's where u-blox fabricates
     * a sliding solution — the "straight line across town" on the
     * 11.7.2026 Ermioni ride), and the polyline breaks across the holes so
     * two distant real points are never bridged by a fake straight
     * connector. Segments with fewer than 2 points are dropped.
     */
    fun cleanTrackSegments(rows: List<GpsRow>): List<List<GpsRow>> {
        val fixes = dedupFixes(validPoints(rows))
        if (fixes.size < 2) return emptyList()
        val fixTicks = DoubleArray(fixes.size) { fixes[it].ticks }
        val zones = blackoutZones(fixTicks)
        val segments = ArrayList<List<GpsRow>>()
        var cur = ArrayList<GpsRow>()
        for (f in fixes) {
            if (zones.any { f.ticks >= it[0] && f.ticks <= it[1] }) continue
            if (cur.isNotEmpty() && f.ticks - cur.last().ticks >= BLACKOUT_GAP_TICKS) {
                if (cur.size >= 2) segments.add(cur)
                cur = ArrayList()
            }
            cur.add(f)
        }
        if (cur.size >= 2) segments.add(cur)
        return segments
    }

    /** Single hops longer than this are GPS glitches (>200 km/h at 1 Hz)
     *  and don't count toward distance. */
    private const val TRACK_MAX_HOP_M = 60.0

    /** Σ haversine within segments — never across the blackout holes
     *  between them — with the [TRACK_MAX_HOP_M] glitch gate per hop. */
    fun segmentsDistanceKm(segments: List<List<GpsRow>>): Double {
        var m = 0.0
        for (seg in segments) {
            for (i in 1 until seg.size) {
                val hop = GpsMath.haversineM(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon)
                if (hop <= TRACK_MAX_HOP_M) m += hop
            }
        }
        return m / 1000.0
    }

    /** Index of the first element ≥ [key] in the sorted [arr]. */
    private fun lowerBound(arr: DoubleArray, key: Double): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Replace NaN speeds (u-blox rows where only the GGA sentence
     * contributed) with the nearest earlier finite value, back-filling the
     * head; an all-NaN track becomes all zeros.
     */
    internal fun fillSpeedGaps(speeds: DoubleArray): DoubleArray {
        val out = speeds.copyOf()
        var last = Double.NaN
        for (i in out.indices) {
            if (out[i].isFinite()) last = out[i] else if (last.isFinite()) out[i] = last
        }
        var next = Double.NaN
        for (i in out.indices.reversed()) {
            if (out[i].isFinite()) next = out[i] else out[i] = if (next.isFinite()) next else 0.0
        }
        return out
    }

    // ---- activity classification (swim / board / land) --------------------

    /** Fallback board threshold when no water mask is available — no one
     *  swims or walks this fast (iOS `RideActivity.boardKmh`). */
    internal const val BOARD_KMH = 6.0

    /** On-water board threshold when the water mask says the point is on
     *  water: sustained ≥ 3.5 km/h means propelled (board / wing), below is
     *  swimming or drifting. Calibrated on the 11.7.2026 Ermioni para-wing
     *  ride: riding stretches held a 4.0–4.3 km/h median while floating
     *  stayed ≤ 2.9 — walking pace never enters here because walking points
     *  are on land pixels. */
    internal const val WATER_BOARD_KMH = 3.5

    /** Mode runs shorter than this get absorbed into a neighbour, so the
     *  track shows sustained activity, not a 1–2 s flicker (iOS
     *  `RideActivity.minRunSec`). */
    internal const val MODE_MIN_RUN_SEC = 20.0

    /** How far (ticks, 30 s) around a blackout hole a point still counts as
     *  "in the water": when the rider is off the board the antenna rides
     *  at/under the surface and the fix timeline gets ≥2 s holes. Near a
     *  hole the reported speed is fabrication anyway, so the hole signal
     *  overrides the speed rule. */
    private const val WET_NEAR_TICKS = 3_000.0

    /** Per-point speed = median of ALL finite speed rows within ±2.5 s.
     *  Never read speed off the cleaned points themselves: `dedupFixes`
     *  drops the RMC half-row twin (same UTC + position as its GGA
     *  sibling) — which is exactly the row that carries the speed. */
    private const val SPEED_WINDOW_TICKS = 250.0

    /**
     * Per-point smoothed activity for the drawn track — colours + labels
     * match the iOS `RideMode` exactly. [rows] is the full CSV (for the
     * fix timeline + speed rows), [pts] the flattened cleaned track from
     * [cleanTrackSegments], [onWater] the per-point [waterMask] (null when
     * offline / tiles unavailable).
     *
     * With a mask, geography decides land vs water and speed decides swim
     * vs board — verified against the 11.7.2026 Ermioni ride, where it
     * reproduces the session minute-by-minute (quay → jump-in → swim →
     * para-wing riding → floating). Without a mask it degrades to the
     * weaker heuristic: fast → board, near a fix hole → swim, else land.
     */
    fun rideModes(rows: List<GpsRow>, pts: List<GpsRow>, onWater: BooleanArray?): List<RideMode> {
        if (pts.isEmpty()) return emptyList()
        // Speed timeline over all rows, sorted by tick.
        val st = rows.filter { it.speedKmhModule.isFinite() && it.ticks.isFinite() }
            .sortedBy { it.ticks }
        val sTicks = DoubleArray(st.size) { st[it].ticks }
        val sVals = DoubleArray(st.size) { st[it].speedKmhModule }
        fun speedAt(t: Double): Double {
            val a = lowerBound(sTicks, t - SPEED_WINDOW_TICKS)
            val b = lowerBound(sTicks, t + SPEED_WINDOW_TICKS + 1e-9)
            if (b <= a) return 0.0
            val w = sVals.copyOfRange(a, b)
            w.sort()
            return w[(w.size - 1) / 2]
        }
        // Wet intervals: ±WET_NEAR_TICKS around every interior fix hole.
        // The leading/trailing convergence zones don't count — a session
        // usually starts and ends on land.
        val fixes = dedupFixes(validPoints(rows))
        val wet = ArrayList<DoubleArray>()
        for (i in 1 until fixes.size) {
            if (fixes[i].ticks - fixes[i - 1].ticks >= BLACKOUT_GAP_TICKS) {
                wet.add(
                    doubleArrayOf(
                        fixes[i - 1].ticks - WET_NEAR_TICKS,
                        fixes[i].ticks + WET_NEAR_TICKS,
                    )
                )
            }
        }
        fun nearHole(t: Double) = wet.any { t >= it[0] && t <= it[1] }
        val raw = IntArray(pts.size) { i ->
            val t = pts[i].ticks
            if (onWater != null) {
                when {
                    !onWater[i] -> RideMode.LAND.ordinal
                    nearHole(t) -> RideMode.SWIM.ordinal
                    speedAt(t) >= WATER_BOARD_KMH -> RideMode.BOARD.ordinal
                    else -> RideMode.SWIM.ordinal
                }
            } else {
                when {
                    speedAt(t) >= BOARD_KMH -> RideMode.BOARD.ordinal
                    nearHole(t) -> RideMode.SWIM.ordinal
                    else -> RideMode.LAND.ordinal
                }
            }
        }
        val ticks = DoubleArray(pts.size) { pts[it].ticks }
        return smoothKeys(raw, ticks, MODE_MIN_RUN_SEC).map { RideMode.entries[it] }
    }

    /** Merge any run of equal keys shorter than [minRunSec] into its longer
     *  temporal neighbour, repeatedly, until only sustained runs remain
     *  (iOS `RideActivity.smoothKeys`, absorb-shortest-first). */
    internal fun smoothKeys(keys: IntArray, ticks: DoubleArray, minRunSec: Double): IntArray {
        if (keys.size <= 1) return keys
        class Run(var s: Int, var e: Int, var k: Int)
        var runs = ArrayList<Run>()
        var s = 0
        for (i in 1..keys.size) {
            if (i == keys.size || keys[i] != keys[s]) {
                runs.add(Run(s, i, keys[s])); s = i
            }
        }
        fun dur(r: Run) = (ticks[r.e - 1] - ticks[r.s]) / GpsMath.TICKS_PER_SEC
        while (runs.size > 1) {
            // Absorb the single shortest sub-threshold run per pass.
            var idx = -1
            var shortest = minRunSec
            for ((i, r) in runs.withIndex()) {
                if (dur(r) < shortest) { shortest = dur(r); idx = i }
            }
            if (idx < 0) break
            val left = runs.getOrNull(idx - 1)
            val right = runs.getOrNull(idx + 1)
            runs[idx].k = when {
                left != null && right != null -> if (dur(left) >= dur(right)) left.k else right.k
                left != null -> left.k
                else -> right!!.k // runs.size > 1 guarantees a neighbour
            }
            val merged = ArrayList<Run>()
            for (r in runs) {
                val last = merged.lastOrNull()
                if (last != null && last.k == r.k) last.e = r.e else merged.add(r)
            }
            runs = merged
        }
        val out = IntArray(keys.size)
        for (r in runs) for (i in r.s until r.e) out[i] = r.k
        return out
    }

    /** OSM standard-carto water fill is a uniform #AAD3DF at every zoom —
     *  verified empirically on the Ermioni harbour tiles (830/872 track
     *  samples hit it exactly). Small tolerance covers shoreline
     *  anti-aliasing. */
    internal fun isWaterColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return abs(r - 170) <= 10 && abs(g - 211) <= 10 && abs(b - 223) <= 10
    }

    /** Zoom for the water lookup: z16 ≈ 2.4 m/px shoreline resolution,
     *  independent of the render zoom. Only tiles actually touched by
     *  track points are fetched (~3 for a harbour session, ~20 for a
     *  10 km downwinder). */
    private const val WATER_ZOOM = 16

    /**
     * Per-point water/land lookup from OSM tile pixels — the Android
     * substitute for the Apple Watch's submersion sensor: the map already
     * knows where the water is. A point is on water when ≥5 of the 3×3
     * pixels around it match the carto water colour (majority vote rides
     * out labels and ferry-route lines drawn over water). Returns null
     * when any tile fetch fails (offline) so callers fall back to the
     * maskless heuristic.
     */
    suspend fun waterMask(pts: List<GpsRow>): BooleanArray? = withContext(Dispatchers.IO) {
        if (pts.isEmpty()) return@withContext null
        val n = 1 shl WATER_ZOOM
        val gx = DoubleArray(pts.size) { lonToPx(pts[it].lon, WATER_ZOOM) }
        val gy = DoubleArray(pts.size) { latToPx(pts[it].lat, WATER_ZOOM) }
        fun tileKey(i: Int): Long {
            val tx = floor(gx[i] / TILE_SIZE).toLong()
            val ty = floor(gy[i] / TILE_SIZE).toLong()
            return (tx shl 32) or (ty and 0xffffffffL)
        }
        val keys = LinkedHashSet<Long>()
        for (i in pts.indices) keys.add(tileKey(i))
        val tiles = HashMap<Long, Bitmap>()
        try {
            val fetched = coroutineScope {
                keys.map { key ->
                    async(Dispatchers.IO) {
                        key to fetchTile(WATER_ZOOM, Math.floorMod((key shr 32).toInt(), n), key.toInt())
                    }
                }.map { it.await() }
            }
            for ((key, bmp) in fetched) if (bmp != null) tiles[key] = bmp
            if (tiles.size != keys.size) return@withContext null
            BooleanArray(pts.size) { i ->
                val tile = tiles[tileKey(i)]!!
                val px = (gx[i] - floor(gx[i] / TILE_SIZE) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
                val py = (gy[i] - floor(gy[i] / TILE_SIZE) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
                var hit = 0
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val sx = (px + dx).coerceIn(0, TILE_SIZE - 1)
                        val sy = (py + dy).coerceIn(0, TILE_SIZE - 1)
                        if (isWaterColor(tile.getPixel(sx, sy))) hit++
                    }
                }
                hit >= 5
            }
        } finally {
            tiles.values.forEach { it.recycle() }
        }
    }

    // ---- PNG rendering ----------------------------------------------------

    /**
     * Render the shareable PNG for [rows] and write it to
     * `getExternalFilesDir(null)/RideMaps/<title>_map.png` (covered by the
     * app's FileProvider paths). Returns the file, or null when there are
     * fewer than two valid fixes or every tile fetch failed.
     */
    suspend fun render(context: Context, rows: List<GpsRow>, title: String): File? =
        withContext(Dispatchers.IO) {
            // Blackout-cleaned segments: no fabricated antenna-under-water
            // positions, no straight connector lines across the fix holes.
            val segments = cleanTrackSegments(rows)
            if (segments.isEmpty()) return@withContext null
            val valid = segments.flatten()

            val width = 1080
            val mapHeight = 1440
            val footerHeight = 240 // iOS parity — 3 stat lines + activity legend

            val lats = DoubleArray(valid.size) { valid[it].lat }
            val lons = DoubleArray(valid.size) { valid[it].lon }
            val bbox = boundingBox(lats, lons) ?: return@withContext null
            val zoom = fitZoom(bbox, width, mapHeight)

            // Viewport origin in global pixel space, centred on the bbox.
            val cx = (lonToPx(bbox[2], zoom) + lonToPx(bbox[3], zoom)) / 2
            val cy = (latToPx(bbox[0], zoom) + latToPx(bbox[1], zoom)) / 2
            val originX = cx - width / 2.0
            val originY = cy - mapHeight / 2.0

            val bmp = Bitmap.createBitmap(width, mapHeight + footerHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(229, 227, 223)) // OSM land colour while tiles load/fail

            if (!drawTiles(canvas, zoom, originX, originY, width, mapHeight)) {
                return@withContext null
            }

            // Track: white casing first for contrast, then activity-coloured
            // segments on top (iOS parity, same widths ×1 since we render
            // at the same 1080 px reference width).
            val px = FloatArray(valid.size)
            val py = FloatArray(valid.size)
            for (i in valid.indices) {
                px[i] = (lonToPx(lons[i], zoom) - originX).toFloat()
                py[i] = (latToPx(lats[i], zoom) - originY).toFloat()
            }
            val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 9f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = Color.argb(230, 255, 255, 255)
            }
            // One sub-path per segment — the path never crosses a blackout.
            val path = Path()
            var off = 0
            for (s in segments) {
                path.moveTo(px[off], py[off])
                for (i in 1 until s.size) path.lineTo(px[off + i], py[off + i])
                off += s.size
            }
            canvas.drawPath(path, casing)

            // Activity-coloured track: swim blue / board green / land orange
            // (iOS colours). Each edge takes its endpoint's mode.
            val modes = rideModes(rows, valid, waterMask(valid))
            val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            }
            off = 0
            for (s in segments) {
                for (i in 1 until s.size) {
                    seg.color = modes[off + i].argb
                    canvas.drawLine(px[off + i - 1], py[off + i - 1], px[off + i], py[off + i], seg)
                }
                off += s.size
            }

            drawMarker(canvas, px[0], py[0], Color.rgb(52, 199, 89))   // systemGreen
            drawMarker(canvas, px[valid.size - 1], py[valid.size - 1], Color.rgb(255, 59, 48)) // systemRed

            // Over ALL rows (RMC half-rows carry the speed with fix column
            // 0), but gated on a nearby valid fix — see robustTopSpeed.
            val topSpeed = robustTopSpeed(rows)
            val distanceKm = segmentsDistanceKm(segments)
            val durMin = (valid.last().ticks - valid.first().ticks) * 0.01 / 60.0
            drawFooter(
                context, canvas, width, mapHeight, footerHeight, topSpeed, distanceKm, durMin,
                RideMode.entries.filter { modes.contains(it) },
            )

            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "RideMaps")
            dir.mkdirs()
            val out = File(dir, "${title}_map.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            // Also publish into Download/MovementLogger/ so the map shows up
            // in Google Files without going through the share sheet.
            // appendAt(base = 0) truncates a previous share of the same ride.
            runCatching { PublicMirror(context).appendAt(out.name, 0L, out.readBytes()) }
            out
        }

    /** Fetch + draw every OSM tile covering the viewport. True if at least
     *  one tile landed (a fully offline phone yields a useless blank map). */
    private suspend fun drawTiles(
        canvas: Canvas, zoom: Int, originX: Double, originY: Double, width: Int, height: Int,
    ): Boolean {
        val n = 1 shl zoom
        val tx0 = floor(originX / TILE_SIZE).toInt()
        val tx1 = floor((originX + width) / TILE_SIZE).toInt()
        val ty0 = floor(originY / TILE_SIZE).toInt().coerceIn(0, n - 1)
        val ty1 = floor((originY + height) / TILE_SIZE).toInt().coerceIn(0, n - 1)

        val jobs = ArrayList<Triple<Int, Int, Bitmap?>>()
        coroutineScope {
            val fetched = (tx0..tx1).flatMap { tx ->
                (ty0..ty1).map { ty ->
                    async(Dispatchers.IO) { Triple(tx, ty, fetchTile(zoom, Math.floorMod(tx, n), ty)) }
                }
            }
            for (d in fetched) jobs.add(d.await())
        }
        var any = false
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((tx, ty, tile) in jobs) {
            if (tile == null) continue
            any = true
            canvas.drawBitmap(
                tile,
                (tx * TILE_SIZE - originX).toFloat(),
                (ty * TILE_SIZE - originY).toFloat(),
                paint,
            )
            tile.recycle()
        }
        return any
    }

    private fun fetchTile(zoom: Int, x: Int, y: Int): Bitmap? = try {
        val conn = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
            .openConnection() as HttpURLConnection
        // OSM tile-usage policy requires an identifying user agent.
        conn.setRequestProperty("User-Agent", "MovementLogger-Android ($SOURCE_URL)")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, fill: Int) {
        val r = 13f
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        canvas.drawCircle(x, y, r, f)
        canvas.drawCircle(x, y, r, s)
    }

    private fun drawFooter(
        context: Context, canvas: Canvas, width: Int, mapHeight: Int, footerHeight: Int,
        topSpeed: Double, distanceKm: Double, durMin: Double, legendModes: List<RideMode>,
    ) {
        val top = mapHeight.toFloat()
        canvas.drawRect(
            0f, top, width.toFloat(), top + footerHeight,
            Paint().apply { color = Color.rgb(15, 15, 15) },
        )

        val pad = 28f
        // Logo — the launcher icon, clipped to a rounded rect like iOS
        // (fixed 134 px, matching the iOS footer regardless of its height).
        val logoSide = 134f
        val logoRect = RectF(pad, top + pad, pad + logoSide, top + pad + logoSide)
        try {
            val icon = context.packageManager.getApplicationIcon(context.applicationInfo)
            canvas.save()
            val clip = Path().apply {
                addRoundRect(logoRect, logoSide * 0.22f, logoSide * 0.22f, Path.Direction.CW)
            }
            canvas.clipPath(clip)
            icon.setBounds(
                logoRect.left.toInt(), logoRect.top.toInt(),
                logoRect.right.toInt(), logoRect.bottom.toInt(),
            )
            icon.draw(canvas)
            canvas.restore()
        } catch (_: Exception) {
            // No logo is better than no PNG.
        }

        val textX = logoRect.right + 22f
        fun text(s: String, y: Float, size: Float, color: Int, tf: Typeface) {
            canvas.drawText(s, textX, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; typeface = tf
            })
        }
        // drawText's y is the BASELINE; iOS draws from the glyph top. Offset
        // by ~0.85×size so the three lines land where the iOS ones do.
        text(
            "Movement Logger", top + pad - 2 + 40 * 0.85f, 40f, Color.WHITE,
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
        )
        text(
            String.format(
                java.util.Locale.US, "Top %.1f km/h   ·   %.2f km   ·   %.0f min",
                topSpeed, distanceKm, max(durMin, 0.0),
            ),
            top + pad + 48 + 30 * 0.85f, 30f, Color.rgb(191, 191, 191), Typeface.DEFAULT,
        )
        text(
            SOURCE_URL, top + pad + 96 + 27 * 0.85f, 27f,
            Color.rgb(115, 204, 255), Typeface.MONOSPACE,
        )

        // Activity legend in the footer's right third (iOS drawLegend).
        val legendW = 330f
        val lx = width - legendW - pad
        fun legendText(s: String, x: Float, yTop: Float, size: Float, color: Int, tf: Typeface) {
            canvas.drawText(s, x, yTop + size * 0.85f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; typeface = tf
            })
        }
        legendText(
            "Activity", lx, top + pad - 4, 24f, Color.rgb(217, 217, 217),
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
        )
        var ly = top + pad + 4 + 40
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (m in legendModes) {
            dot.color = m.argb
            canvas.drawCircle(lx + 11f, ly + 4 + 11f, 11f, dot)
            legendText(m.label, lx + 32f, ly, 26f, Color.WHITE, Typeface.DEFAULT)
            ly += 40
        }
    }
}

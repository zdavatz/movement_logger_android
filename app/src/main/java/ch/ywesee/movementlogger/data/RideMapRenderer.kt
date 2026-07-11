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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Shareable ride-map PNG — Android port of the iOS `RideMapRenderer`
 * (`RideMap.swift`): real map tiles under a speed-coloured track, with the
 * app logo, ride stats and the GitHub source link baked into a branded
 * footer. iOS snapshots Apple Maps via `MKMapSnapshotter`; here we stitch
 * OpenStreetMap tiles ourselves (Web-Mercator math below), which keeps the
 * output identical in spirit and needs no API key.
 *
 * The pure geometry/statistics helpers are `internal` and JVM-only (no
 * android.* types) so `RideMapMathTest` can cover them.
 */
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
            val footerHeight = 190

            val lats = DoubleArray(valid.size) { valid[it].lat }
            val lons = DoubleArray(valid.size) { valid[it].lon }
            val speeds = fillSpeedGaps(DoubleArray(valid.size) { valid[it].speedKmhModule })
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

            // Track: white casing first for contrast, then speed-coloured
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

            val vMax = max(robustMaxSpeed(speeds), 5.0)
            val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            }
            off = 0
            for (s in segments) {
                for (i in 1 until s.size) {
                    seg.color = Color.HSVToColor(
                        floatArrayOf((speedHue(speeds[off + i], vMax) * 360).toFloat(), 0.9f, 0.95f)
                    )
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
            drawFooter(context, canvas, width, mapHeight, footerHeight, topSpeed, distanceKm, durMin)

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
        topSpeed: Double, distanceKm: Double, durMin: Double,
    ) {
        val top = mapHeight.toFloat()
        canvas.drawRect(
            0f, top, width.toFloat(), top + footerHeight,
            Paint().apply { color = Color.rgb(15, 15, 15) },
        )

        val pad = 28f
        // Logo — the launcher icon, clipped to a rounded rect like iOS.
        val logoSide = footerHeight - pad * 2
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
    }
}

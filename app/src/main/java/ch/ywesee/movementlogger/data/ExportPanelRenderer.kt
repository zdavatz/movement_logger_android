package ch.ywesee.movementlogger.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos

/**
 * Mirror of the Compose Canvas panels in `ReplayScreen.kt`, but using raw
 * `android.graphics.Canvas` so it can be invoked off the UI thread by the
 * video exporter. The data passed in is expected to already be trimmed to
 * the video's time window (see `ReplayTrim`).
 *
 * Output frame layout (all four panels stacked vertically):
 *
 * ```
 * ┌──────────────────────┐  ← y = 0
 * │       Speed          │  panelH
 * ├──────────────────────┤
 * │       Pitch          │  panelH
 * ├──────────────────────┤
 * │  Height (baro+fused) │  panelH
 * ├──────────────────────┤
 * │      GPS track       │  panelH
 * └──────────────────────┘  ← y = panelH * 4
 * ```
 */
class ExportPanelRenderer(
    private val trimmed: ReplayTrim.TrimmedSession,
    private val windowStartMs: Long,
    private val windowEndMs: Long,
    private val outWidth: Int,
    private val panelHeight: Int,
) {
    val totalHeight: Int = panelHeight * 4

    // Static maxima for stable axes across the export (don't auto-rescale per frame).
    private val maxSpeed: Double
    private val absMaxPitch: Double
    private val heightMin: Double
    private val heightMax: Double
    private val gpsBounds: GpsProjection

    init {
        val speed = trimmed.speedSmoothedKmh
        maxSpeed = (speed.maxOrNull() ?: 0.0).coerceAtLeast(5.0)

        val pitch = trimmed.pitchDeg
        var amp = 0.0
        for (v in pitch) if (abs(v) > amp) amp = abs(v)
        absMaxPitch = amp.coerceAtLeast(5.0)

        var hMin = Double.POSITIVE_INFINITY
        var hMax = Double.NEGATIVE_INFINITY
        for (v in trimmed.baroHeightM) { if (v < hMin) hMin = v; if (v > hMax) hMax = v }
        for (v in trimmed.fusedHeightM) { if (v < hMin) hMin = v; if (v > hMax) hMax = v }
        if (!hMin.isFinite() || !hMax.isFinite()) { hMin = -1.0; hMax = 1.0 }
        if (hMax - hMin < 0.2) { hMin -= 0.1; hMax += 0.1 }
        heightMin = hMin
        heightMax = hMax

        gpsBounds = GpsProjection.fromRows(trimmed.gpsRows)
    }

    /** Render the four-panel stack at the given absolute UTC time. */
    fun renderAt(timeMs: Long): Bitmap {
        val bmp = Bitmap.createBitmap(outWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Background.
        canvas.drawColor(Color.parseColor("#F2EFF7"))

        val w = outWidth.toFloat()
        val ph = panelHeight.toFloat()
        drawSpeed(canvas, 0f, 0f, w, ph, timeMs)
        drawPitch(canvas, 0f, ph, w, ph, timeMs)
        drawHeight(canvas, 0f, ph * 2, w, ph, timeMs)
        drawGpsTrack(canvas, 0f, ph * 3, w, ph, timeMs)
        return bmp
    }

    // ── Speed ────────────────────────────────────────────────────────────
    private fun drawSpeed(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, timeMs: Long) {
        val speeds = trimmed.speedSmoothedKmh
        val times = trimmed.gpsAbsTimesMs
        drawPanelBackground(canvas, x, y, w, h)
        drawTitle(canvas, "Speed (km/h)", x, y)

        val plot = innerRect(x, y, w, h)
        val n = speeds.size
        if (n >= 2) {
            val path = Path()
            for (i in 0 until n) {
                val px = plot.left + i.toFloat() / (n - 1) * plot.width()
                val py = plot.bottom - (speeds[i] / maxSpeed * plot.height()).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, linePrimary)
        }
        val curIdx = nearestIdx(times, timeMs)
        val curSpeed = if (curIdx in 0 until n) speeds[curIdx] else 0.0
        drawCursor(canvas, plot, curIdx, n)
        drawNowMaxLabels(canvas, x, y, "now %.1f".format(curSpeed), "max %.1f".format(maxSpeed))
    }

    // ── Pitch ────────────────────────────────────────────────────────────
    private fun drawPitch(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, timeMs: Long) {
        val pitch = trimmed.pitchDeg
        val times = trimmed.sensorAbsTimesMs
        drawPanelBackground(canvas, x, y, w, h)
        drawTitle(canvas, "Pitch / Nasenwinkel (°)", x, y)

        val plot = innerRect(x, y, w, h)
        val zeroY = plot.centerY()
        canvas.drawLine(plot.left, zeroY, plot.right, zeroY, zeroLinePaint)
        val n = pitch.size
        if (n >= 2) {
            val path = Path()
            for (i in 0 until n) {
                val px = plot.left + i.toFloat() / (n - 1) * plot.width()
                val py = zeroY - (pitch[i] / absMaxPitch * plot.height() / 2).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, lineSecondary)
        }
        val curIdx = nearestIdx(times, timeMs)
        val curPitch = if (curIdx in 0 until n) pitch[curIdx] else 0.0
        drawCursor(canvas, plot, curIdx, n)
        drawNowMaxLabels(canvas, x, y, "now %+.1f°".format(curPitch), "±%.0f°".format(absMaxPitch))
    }

    // ── Height ───────────────────────────────────────────────────────────
    private fun drawHeight(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, timeMs: Long) {
        val baro = trimmed.baroHeightM
        val fused = trimmed.fusedHeightM
        val times = trimmed.sensorAbsTimesMs
        drawPanelBackground(canvas, x, y, w, h)
        drawTitle(canvas, "Height above water (m)", x, y)

        val plot = innerRect(x, y, w, h)
        val span = (heightMax - heightMin).coerceAtLeast(1e-3)

        fun drawSeries(arr: DoubleArray, paint: Paint) {
            if (arr.size < 2) return
            val path = Path()
            for (i in arr.indices) {
                val px = plot.left + i.toFloat() / (arr.size - 1) * plot.width()
                val py = plot.bottom - ((arr[i] - heightMin) / span * plot.height()).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
        drawSeries(baro, lineBaro)
        drawSeries(fused, lineFused)

        val curIdx = nearestIdx(times, timeMs)
        val curBaro = if (curIdx in baro.indices) baro[curIdx] else 0.0
        val curFused = if (curIdx in fused.indices) fused[curIdx] else 0.0
        drawCursor(canvas, plot, curIdx, fused.size)
        drawLabel(canvas, x, y, 0, "fused %+.2f m".format(curFused), Color.parseColor("#5E35B1"))
        drawLabel(canvas, x, y, 1, "baro  %+.2f m".format(curBaro), Color.parseColor("#777777"))
        drawLabel(canvas, x, y, 2, "range %+.2f .. %+.2f".format(heightMin, heightMax), Color.parseColor("#999999"))
    }

    // ── GPS track ───────────────────────────────────────────────────────
    private fun drawGpsTrack(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, timeMs: Long) {
        drawPanelBackground(canvas, x, y, w, h)
        drawTitle(canvas, "GPS track", x, y)
        val plot = innerRect(x, y, w, h)
        val rows = trimmed.gpsRows
        if (rows.size < 2) return
        val proj = gpsBounds
        val path = Path()
        var first = true
        for (r in rows) {
            val (px, py) = proj.toCanvas(r.lat, r.lon, plot)
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
        }
        canvas.drawPath(path, gpsTrackPaint)

        val curIdx = nearestIdx(trimmed.gpsAbsTimesMs, timeMs)
        if (curIdx in rows.indices) {
            val (px, py) = proj.toCanvas(rows[curIdx].lat, rows[curIdx].lon, plot)
            canvas.drawCircle(px, py, panelHeight * 0.025f, gpsDotPaint)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private fun innerRect(x: Float, y: Float, w: Float, h: Float): RectF {
        val pad = panelHeight * 0.06f
        val titleH = titlePaint.textSize + pad
        return RectF(x + pad, y + titleH, x + w - pad, y + h - pad)
    }

    private fun drawPanelBackground(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val pad = panelHeight * 0.04f
        canvas.drawRoundRect(
            x + pad, y + pad, x + w - pad, y + h - pad,
            pad, pad, panelBgPaint,
        )
    }

    private fun drawTitle(canvas: Canvas, text: String, x: Float, y: Float) {
        val pad = panelHeight * 0.06f
        canvas.drawText(text, x + pad, y + pad + titlePaint.textSize, titlePaint)
    }

    private fun drawCursor(canvas: Canvas, plot: RectF, idx: Int, n: Int) {
        if (idx !in 0 until n || n < 2) return
        val cx = plot.left + idx.toFloat() / (n - 1) * plot.width()
        canvas.drawLine(cx, plot.top, cx, plot.bottom, cursorPaint)
    }

    private fun drawNowMaxLabels(canvas: Canvas, x: Float, y: Float, now: String, max: String) {
        drawLabel(canvas, x, y, 0, now, Color.parseColor("#222222"))
        drawLabel(canvas, x, y, 1, max, Color.parseColor("#777777"))
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, line: Int, text: String, color: Int) {
        val pad = panelHeight * 0.06f
        labelPaint.color = color
        val baseline = y + pad + titlePaint.textSize + (line + 1) * (labelPaint.textSize * 1.15f) + pad * 0.3f
        canvas.drawText(text, x + pad, baseline, labelPaint)
    }

    /** Binary search for the row whose `times[i]` is closest to `target`. */
    private fun nearestIdx(times: LongArray, target: Long): Int {
        if (times.isEmpty()) return -1
        if (target <= times[0]) return 0
        if (target >= times[times.size - 1]) return times.size - 1
        var lo = 0
        var hi = times.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= target) lo = mid else hi = mid
        }
        return if (target - times[lo] <= times[hi] - target) lo else hi
    }

    // ── paints (created once per renderer, mutated for label colors) ─────
    private val panelBgPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint().apply {
        color = Color.parseColor("#222222")
        isAntiAlias = true
        textSize = panelHeight * 0.085f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textSize = panelHeight * 0.07f
        typeface = Typeface.MONOSPACE
    }
    private val zeroLinePaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = panelHeight * 0.004f
        isAntiAlias = true
    }
    private val linePrimary = Paint().apply {
        color = Color.parseColor("#5E35B1")
        style = Paint.Style.STROKE
        strokeWidth = panelHeight * 0.012f
        isAntiAlias = true
    }
    private val lineSecondary = Paint().apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = panelHeight * 0.012f
        isAntiAlias = true
    }
    private val lineBaro = Paint().apply {
        color = Color.parseColor("#888888")
        style = Paint.Style.STROKE
        strokeWidth = panelHeight * 0.006f
        isAntiAlias = true
    }
    private val lineFused = Paint().apply {
        color = Color.parseColor("#5E35B1")
        style = Paint.Style.STROKE
        strokeWidth = panelHeight * 0.013f
        isAntiAlias = true
    }
    private val gpsTrackPaint = Paint().apply {
        color = Color.parseColor("#8D2C8D")
        style = Paint.Style.STROKE
        strokeWidth = panelHeight * 0.008f
        isAntiAlias = true
    }
    private val gpsDotPaint = Paint().apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val cursorPaint = Paint().apply {
        color = Color.parseColor("#D32F2F")
        strokeWidth = panelHeight * 0.008f
        isAntiAlias = true
    }

    private class GpsProjection(
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double,
        val lonScale: Double,
    ) {
        fun toCanvas(lat: Double, lon: Double, plot: RectF): Pair<Float, Float> {
            val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
            val lonSpan = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-9)
            val nx = ((lon - minLon) * lonScale / lonSpan).toFloat()
            val ny = ((lat - minLat) / latSpan).toFloat()
            return plot.left + nx * plot.width() to plot.bottom - ny * plot.height()
        }
        companion object {
            fun fromRows(rows: List<GpsRow>): GpsProjection {
                var minLat = Double.POSITIVE_INFINITY
                var maxLat = Double.NEGATIVE_INFINITY
                var minLon = Double.POSITIVE_INFINITY
                var maxLon = Double.NEGATIVE_INFINITY
                for (r in rows) {
                    if (r.lat < minLat) minLat = r.lat
                    if (r.lat > maxLat) maxLat = r.lat
                    if (r.lon < minLon) minLon = r.lon
                    if (r.lon > maxLon) maxLon = r.lon
                }
                if (!minLat.isFinite()) { minLat = 0.0; maxLat = 0.0; minLon = 0.0; maxLon = 0.0 }
                val meanLat = (minLat + maxLat) / 2.0
                val lonScale = cos(Math.toRadians(meanLat))
                return GpsProjection(minLat, maxLat, minLon, maxLon, lonScale)
            }
        }
    }
}

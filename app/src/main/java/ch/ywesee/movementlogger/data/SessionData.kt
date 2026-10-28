package ch.ywesee.movementlogger.data

import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One recorded session drawn straight from the box's `SENSnnn.CSV` +
 * `GPSnnn.CSV` — no video, no fusion pipeline. Port of the desktop's
 * Session tab (`stbox-viz-gui/src/session_view.rs`); the UI is
 * `ui/SessionScreen.kt`.
 *
 * Both files are stamped with the box's own `HAL_GetTick()` ms, so they
 * share one clock: t = 0 is the first sample of either file, every array
 * here is in seconds from there. Wall-clock labels come from [clock].
 *
 * The sensor file is ~100 Hz (3 h ≈ 1 M rows), so it is parsed in ONE
 * streaming pass into primitive arrays — never a `List<SensorRow>`, which
 * would be ~100 MB of heap on-device (the 11.7.2026 OOM lesson) — and the
 * charts only ever draw [decimate]d min/max buckets.
 */
class SessionData(
    val sensName: String,
    val gpsName: String?,
    /** Box tick (10 ms units) of t = 0. */
    val t0Ticks: Double,
    val durationS: Double,
    val clock: SessionClock,

    val sensT: DoubleArray,
    val accG: FloatArray,
    val gyroDps: FloatArray,
    val pitchDeg: FloatArray,
    val rollDeg: FloatArray,
    val heightM: FloatArray,

    val gpsT: DoubleArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** Module speed, NaN on rows without a fix. */
    val speedKmh: FloatArray,
    val nsat: IntArray,
    /** Indices into the gps arrays that are drawable track points (fix,
     *  non-zero position, no multipath hop). */
    val track: IntArray,
    /** Top of the speed colour scale: 95th-percentile track speed, ≥ 5. */
    val speedScale: Double,
    val distanceM: Double,
    val maxSpeedKmh: Double,
) {
    /** Median fix interval over the track → Hz; 0 without a track. */
    val gpsRateHz: Double by lazy {
        val dts = (1 until track.size).map { gpsT[track[it]] - gpsT[track[it - 1]] }.filter { it > 0 }.sorted()
        if (dts.isEmpty()) 0.0 else 1.0 / dts[dts.size / 2]
    }

    /** Wall-clock label for session second [x] (`HH:MM:SS`, or `HH:MM:SS.s`). */
    fun label(x: Double, tenths: Boolean, tz: TimeZone = TimeZone.getDefault()): String? =
        clock.localSecondOfDay(t0Ticks + x * 100.0, tz)?.let { hms(it, tenths) }

    /** Index of the last track point at or before session second [x], or -1. */
    fun trackIndexAt(x: Double): Int {
        var lo = 0
        var hi = track.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (gpsT[track[mid]] <= x) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    /** One-line readout of every series at session second [x]. */
    fun readout(x: Double, tz: TimeZone = TimeZone.getDefault()): String {
        // Locale.US: a German decimal comma would make "47,4, 8,5" unreadable.
        fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)
        val sb = StringBuilder(label(x, true, tz) ?: f("%.1f s", x))
        valueAt(gpsT, speedKmh, x)?.let { sb.append(f("  %.1f km/h", it)) }
        val gi = indexAt(gpsT, x)
        if (gi >= 0 && (lat[gi] != 0.0 || lon[gi] != 0.0)) {
            sb.append(f("  %.6f, %.6f", lat[gi], lon[gi]))
            sb.append("  ${nsat[gi]} sats")
        }
        valueAt(sensT, accG, x)?.let { sb.append(f("  |acc| %.2f g", it)) }
        val p = valueAt(sensT, pitchDeg, x)
        val r = valueAt(sensT, rollDeg, x)
        if (p != null && r != null) sb.append(f("  pitch %+.0f°  roll %+.0f°", p, r))
        valueAt(sensT, heightM, x)?.let { sb.append(f("  height %+.1f m", it)) }
        return sb.toString()
    }

    companion object {
        /** Value of [y] at [x]: nearest sample at or before, null if none / NaN. */
        internal fun valueAt(t: DoubleArray, y: FloatArray, x: Double): Float? {
            val i = indexAt(t, x)
            return if (i >= 0 && y[i].isFinite()) y[i] else null
        }

        /** Index of the last sample with `t ≤ x` (0 when x precedes all), -1 if empty. */
        internal fun indexAt(t: DoubleArray, x: Double): Int {
            if (t.isEmpty()) return -1
            return (upperBound(t, x) - 1).coerceAtLeast(0)
        }
    }
}

/** How box ticks map to wall-clock time. */
sealed class SessionClock {
    /** Host `# SYNC` anchors (phone clock, drift-free) — preferred. */
    data class Anchors(val anchors: List<SyncAnchor>) : SessionClock()

    /** GPS UTC: UTC seconds-of-day = box seconds + [offsetS] (no date). */
    data class GpsUtc(val offsetS: Double) : SessionClock()

    data object None : SessionClock()

    /** Local seconds-of-day at box tick [ticks], null when unknown. */
    fun localSecondOfDay(ticks: Double, tz: TimeZone): Double? = when (this) {
        is Anchors -> {
            val epoch = SyncTimeAlign.absTimesFromSyncAnchors(doubleArrayOf(ticks), anchors)[0]
            (epoch + tz.getOffset(epoch)) / 1000.0
        }
        is GpsUtc -> {
            // No date in the GPS string: use today's zone offset (the
            // desktop does the same). Session-day DST edge cases aside, this
            // is the offset the rider's watch showed.
            ticks / GpsMath.TICKS_PER_SEC + offsetS + tz.getOffset(System.currentTimeMillis()) / 1000.0
        }
        None -> null
    }
}

/** Loader progress: file being read, bytes done / total. */
fun interface SessionProgress {
    fun update(file: String, done: Long, total: Long)
}

object SessionLoader {
    /** Min/max buckets per chart — two points each, so ≤ 2400 vertices per
     *  series however long the session. */
    const val PLOT_BUCKETS = 1200

    /** GPS hops faster than this between consecutive fixes are multipath
     *  jumps, not movement (same clip as the desktop's Race / Session tabs). */
    const val MAX_JUMP_KMH = 60.0

    /** Pitch/roll come from the accelerometer alone, smoothed over this
     *  many seconds so pumping accelerations don't dominate the angle. */
    const val ANGLE_SMOOTH_S = 0.5

    /**
     * The GPS file that belongs to [sensName] among [candidates]:
     * `SENS003.CSV` → `GPS003.CSV`, `Sens003.csv` → `Gps003.csv`,
     * `SensPhone_x.csv` → `GpsPhone_x.csv`. Case-insensitive (the box
     * writes upper case, older firmware and the phone logger mixed case).
     */
    fun matchingGpsName(sensName: String, candidates: List<String>): String? {
        if (!sensName.lowercase().startsWith("sens")) return null
        val want = "gps" + sensName.substring(4).lowercase()
        return candidates.firstOrNull { it.lowercase() == want }
    }

    @Throws(IOException::class)
    fun load(sens: File, gps: File?, progress: SessionProgress? = null): SessionData {
        // GPS first: it's small, so a wrong/missing GPS file fails fast.
        val gpsRows = gps?.let { f ->
            counting(f, progress).use { CsvParsers.parseGpsStream(it) }
        } ?: emptyList()
        val s = counting(sens, progress).use(::parseSensor)
        return build(sens.name, gps?.name, s, gpsRows)
    }

    /** Streamed sensor columns, already reduced to what the view needs. */
    internal class SensorArrays(
        val ticks: DoubleArray,
        val ax: FloatArray, val ay: FloatArray, val az: FloatArray,
        /** |gyro| in mdps. */
        val gyro: FloatArray,
        val pressure: FloatArray,
        val n: Int,
        val anchors: List<SyncAnchor>,
    )

    /**
     * One pass over a `Sens*` CSV (both header schemas) into primitive
     * arrays, collecting `# SYNC` anchors on the way. Field walk + inline
     * number parse: no per-row `split`, no per-field strings. Malformed
     * rows (a torn tail of a live-mirrored file) are skipped, not fatal.
     */
    @Throws(IOException::class)
    internal fun parseSensor(input: InputStream): SensorArrays {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
        val header = reader.readLine() ?: throw IOException("sensor csv: empty file")
        val c = CsvParsers.SensorColumns(header)
        val need = intArrayOf(c.t, c.ax, c.ay, c.az, c.gx, c.gy, c.gz, c.p)
        val maxIdx = need.max()
        val wanted = BooleanArray(maxIdx + 1).also { w -> need.forEach { w[it] = true } }
        val f = DoubleArray(maxIdx + 1)
        var cap = 1 shl 16
        var ticks = DoubleArray(cap)
        var ax = FloatArray(cap); var ay = FloatArray(cap); var az = FloatArray(cap)
        var gyro = FloatArray(cap); var pres = FloatArray(cap)
        var n = 0
        val anchors = ArrayList<SyncAnchor>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                CsvParsers.parseSyncLine(line.trim(), c.tickDiv)?.let(anchors::add)
                continue
            }
            var start = 0
            var idx = 0
            var ok = true
            while (idx <= maxIdx) {
                val comma = line.indexOf(',', start)
                val end = if (comma < 0) line.length else comma
                if (wanted[idx]) {
                    val v = parseFieldOrNan(line, start, end)
                    if (v.isNaN()) { ok = false; break }
                    f[idx] = v
                }
                if (comma < 0 && idx < maxIdx) { ok = false; break }
                start = end + 1
                idx++
            }
            if (!ok) continue
            if (n == cap) {
                cap *= 2
                ticks = ticks.copyOf(cap)
                ax = ax.copyOf(cap); ay = ay.copyOf(cap); az = az.copyOf(cap)
                gyro = gyro.copyOf(cap); pres = pres.copyOf(cap)
            }
            ticks[n] = f[c.t] / c.tickDiv
            ax[n] = f[c.ax].toFloat(); ay[n] = f[c.ay].toFloat(); az[n] = f[c.az].toFloat()
            gyro[n] = sqrt(f[c.gx] * f[c.gx] + f[c.gy] * f[c.gy] + f[c.gz] * f[c.gz]).toFloat()
            pres[n] = f[c.p].toFloat()
            n++
        }
        return SensorArrays(ticks, ax, ay, az, gyro, pres, n, CsvParsers.sortDedupeAnchors(anchors))
    }

    internal fun build(sensName: String, gpsName: String?, s: SensorArrays, gpsRows: List<GpsRow>): SessionData {
        val n = s.n
        if (n == 0) throw IOException("$sensName: no sensor rows")
        val t0 = minOf(s.ticks[0], gpsRows.firstOrNull()?.ticks ?: Double.MAX_VALUE)
        val sensT = DoubleArray(n) { (s.ticks[it] - t0) / GpsMath.TICKS_PER_SEC }
        val gpsT = DoubleArray(gpsRows.size) { (gpsRows[it].ticks - t0) / GpsMath.TICKS_PER_SEC }
        val duration = maxOf(sensT[n - 1], gpsT.lastOrNull() ?: 0.0)

        val accG = FloatArray(n) {
            sqrt(s.ax[it] * s.ax[it] + s.ay[it] * s.ay[it] + s.az[it] * s.az[it]) / 1000f
        }
        val gyroDps = FloatArray(n) { s.gyro[it] / 1000f }

        // Box frame: nose = +Y, up = +Z. The phone-style long-axis-X
        // formulas swap pitch and roll on this box (see "Board angles").
        val hz = if (n > 1 && sensT[n - 1] > 0) (n / sensT[n - 1]).coerceIn(1.0, 1000.0) else 100.0
        val win = Math.round(ANGLE_SMOOTH_S * hz).toInt()
        val ax = smooth(s.ax, n, win)
        val ay = smooth(s.ay, n, win)
        val az = smooth(s.az, n, win)
        val pitch = FloatArray(n)
        val roll = FloatArray(n)
        for (i in 0 until n) {
            pitch[i] = Math.toDegrees(atan2(ay[i].toDouble(), sqrt((ax[i] * ax[i] + az[i] * az[i]).toDouble()))).toFloat()
            roll[i] = Math.toDegrees(atan2(ax[i].toDouble(), az[i].toDouble())).toFloat()
        }

        // Baro height relative to the start (international barometric
        // formula, reference = median of the first ~second).
        val first = s.pressure.copyOf(minOf(n, 100)).sorted()
        val p0 = first[first.size / 2].toDouble()
        val rawH = FloatArray(n) { baroHeightM(s.pressure[it].toDouble(), p0).toFloat() }
        val height = smooth(rawH, n, win)

        val lat = DoubleArray(gpsRows.size) { gpsRows[it].lat }
        val lon = DoubleArray(gpsRows.size) { gpsRows[it].lon }
        val speed = FloatArray(gpsRows.size) {
            val r = gpsRows[it]
            if (r.fix > 0) r.speedKmhModule.toFloat() else Float.NaN
        }
        val nsat = IntArray(gpsRows.size) { gpsRows[it].numSat }

        val track = ArrayList<Int>(gpsRows.size)
        var distance = 0.0
        for (i in gpsRows.indices) {
            val r = gpsRows[i]
            if (r.fix <= 0 || (r.lat == 0.0 && r.lon == 0.0)) continue
            val j = track.lastOrNull()
            if (j != null) {
                val d = GpsMath.haversineM(lat[j], lon[j], lat[i], lon[i])
                val dt = (gpsT[i] - gpsT[j]).coerceAtLeast(0.05)
                if (d / dt * 3.6 > MAX_JUMP_KMH) continue
                distance += d
            }
            track.add(i)
        }
        val trackSpeeds = DoubleArray(track.size) { speed[track[it]].toDouble() }
        val maxSpeed = trackSpeeds.filter { it.isFinite() }.maxOrNull() ?: 0.0
        // Colour scale tops out at the 95th percentile so one spike doesn't
        // wash the whole track blue.
        val scale = RideMapRenderer.robustMaxSpeed(trackSpeeds).coerceAtLeast(5.0)

        val clock = if (s.anchors.isNotEmpty()) {
            SessionClock.Anchors(s.anchors)
        } else {
            // Median of (UTC seconds-of-day − box seconds) over fixed rows.
            val offs = gpsRows.asSequence()
                .filter { it.fix > 0 }
                .mapNotNull { r -> GpsTime.parseHhmmssSs(r.utc)?.let { it - r.ticks / GpsMath.TICKS_PER_SEC } }
                .take(200).sorted().toList()
            if (offs.isEmpty()) SessionClock.None else SessionClock.GpsUtc(offs[offs.size / 2])
        }

        return SessionData(
            sensName = sensName, gpsName = gpsName, t0Ticks = t0, durationS = duration, clock = clock,
            sensT = sensT, accG = accG, gyroDps = gyroDps, pitchDeg = pitch, rollDeg = roll, heightM = height,
            gpsT = gpsT, lat = lat, lon = lon, speedKmh = speed, nsat = nsat,
            track = track.toIntArray(), speedScale = scale, distanceM = distance, maxSpeedKmh = maxSpeed,
        )
    }

    /** Track colour: blue (slow) → green → red (fast), [scale] = full red. */
    fun speedArgb(v: Double, scale: Double): Int {
        val f = if (v.isFinite()) (v / scale).coerceIn(0.0, 1.0) else 0.0
        val (r, g, b) = if (f < 0.5) {
            val k = f * 2
            Triple(0.0, k, 1 - k)
        } else {
            val k = (f - 0.5) * 2
            Triple(k, 1 - k, 0.0)
        }
        fun ch(x: Double) = (40 + 215 * x).toInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** International barometric formula, metres above the [p0] level. */
    internal fun baroHeightM(p: Double, p0: Double): Double = 44_330.0 * (1.0 - (p / p0).pow(1.0 / 5.255))

    /** Centred moving average over [win] samples (running sum, O(n)) —
     *  window `[i − win/2, i + win/2]`, shrinking at the edges. */
    internal fun smooth(y: FloatArray, n: Int, win: Int): FloatArray {
        if (win <= 1 || n == 0) return y.copyOf(n)
        val half = win / 2
        val out = FloatArray(n)
        var sum = 0.0
        var a = 0 // window [a, b)
        var b = 0
        for (i in 0 until n) {
            val na = (i - half).coerceAtLeast(0)
            val nb = (i + half + 1).coerceAtMost(n)
            while (b < nb) { sum += y[b]; b++ }
            while (a < na) { sum -= y[a]; a++ }
            out[i] = (sum / (b - a)).toFloat()
        }
        return out
    }

    /**
     * Min/max pairs of [y] over [buckets] buckets of the visible range
     * `[lo, hi]` ([t] sorted), interleaved `[t0, y0, t1, y1, …]`. NaN
     * samples are skipped; a bucket with none is a gap. Short ranges come
     * back raw.
     */
    fun decimate(t: DoubleArray, y: FloatArray, lo: Double, hi: Double, buckets: Int = PLOT_BUCKETS): FloatArray {
        val a = (lowerBound(t, lo) - 1).coerceAtLeast(0)
        val b = (upperBound(t, hi) + 1).coerceAtMost(t.size)
        if (b <= a) return FloatArray(0)
        val n = b - a
        if (n <= buckets * 2) {
            var k = 0
            val out = FloatArray(n * 2)
            for (i in a until b) if (y[i].isFinite()) { out[k++] = t[i].toFloat(); out[k++] = y[i] }
            return out.copyOf(k)
        }
        val out = FloatArray(buckets * 4)
        var k = 0
        for (q in 0 until buckets) {
            val s = a + (n.toLong() * q / buckets).toInt()
            val e = a + (n.toLong() * (q + 1) / buckets).toInt()
            var iMin = -1
            var iMax = -1
            for (i in s until e) {
                val v = y[i]
                if (!v.isFinite()) continue
                if (iMin < 0 || v < y[iMin]) iMin = i
                if (iMax < 0 || v > y[iMax]) iMax = i
            }
            if (iMin < 0) continue
            val p = minOf(iMin, iMax)
            val r = maxOf(iMin, iMax)
            out[k++] = t[p].toFloat(); out[k++] = y[p]
            if (r != p) { out[k++] = t[r].toFloat(); out[k++] = y[r] }
        }
        return out.copyOf(k)
    }

    private fun counting(f: File, progress: SessionProgress?): InputStream {
        val total = f.length()
        val name = f.name
        progress?.update(name, 0, total)
        return object : FilterInputStream(f.inputStream()) {
            private var done = 0L
            private var reported = 0L
            private fun step(n: Int) {
                if (n <= 0) return
                done += n
                if (done - reported >= 512 * 1024 || done == total) {
                    reported = done
                    progress?.update(name, done, total)
                }
            }
            override fun read(): Int = super.read().also { if (it >= 0) step(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also(::step)
        }
    }
}

/** First index with `t[i] ≥ x`. */
internal fun lowerBound(t: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = t.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (t[mid] < x) lo = mid + 1 else hi = mid
    }
    return lo
}

/** First index with `t[i] > x`. */
internal fun upperBound(t: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = t.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (t[mid] <= x) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Seconds-of-day → `HH:MM:SS` (or `HH:MM:SS.s`), wrapping at midnight. */
internal fun hms(sod: Double, tenths: Boolean): String {
    val d = ((sod % 86_400.0) + 86_400.0) % 86_400.0
    return if (tenths) {
        val tenth = floor(d * 10.0).toLong()
        val whole = tenth / 10
        "%02d:%02d:%02d.%d".format(whole / 3600, (whole / 60) % 60, whole % 60, tenth % 10)
    } else {
        val whole = floor(d).toLong()
        "%02d:%02d:%02d".format(whole / 3600, (whole / 60) % 60, whole % 60)
    }
}

package ch.ywesee.movementlogger.data

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS post-processing — Kotlin port of `gps.rs` from the desktop project.
 *
 * The u-blox MAX-M10S Doppler-based `Speed [km/h]` column in `Gps*.csv`
 * is unreliable on this hardware (~0.1 km/h while position deltas
 * showed sustained 10-30 km/h flight). All consumers should use the
 * position-derived speed from [positionDerivedSpeedKmh].
 */
object GpsMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Box's ThreadX time base: 1 tick = 10 ms. */
    const val TICKS_PER_SEC = 100.0

    /** Default acceleration threshold for [rejectAccOutliers] (km/h per s). */
    const val DEFAULT_MAX_ACCEL_KMH_PER_S = 15.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val dlat = Math.toRadians(lat2 - lat1)
        val dlon = Math.toRadians(lon2 - lon1)
        val a = sin(dlat / 2.0).let { it * it } +
            cos(lat1r) * cos(lat2r) * sin(dlon / 2.0).let { it * it }
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Position-derived speed per row (km/h). The first row is 0. */
    fun positionDerivedSpeedKmh(gps: List<GpsRow>): DoubleArray {
        val n = gps.size
        val out = DoubleArray(n)
        for (i in 1 until n) {
            val dist = haversineM(gps[i - 1].lat, gps[i - 1].lon, gps[i].lat, gps[i].lon)
            val dt = (gps[i].ticks - gps[i - 1].ticks) / TICKS_PER_SEC
            if (dt > 0.05) {
                out[i] = (dist / dt) * 3.6
            }
        }
        return out
    }

    /**
     * Reject unphysical jumps by comparing |Δspeed| against a maximum
     * plausible longitudinal acceleration. Pumpfoil/SUPfoil paddle-starts
     * produce ~1-3 m/s², so anything above ~4 m/s² (~15 km/h/s) is almost
     * certainly a multipath-induced position jump.
     *
     * Mirrors `reject_acc_outliers` in gps.rs — keeps the previous valid
     * sample as baseline so a glitch doesn't poison everything downstream.
     */
    fun rejectAccOutliers(
        gps: List<GpsRow>,
        rawKmh: DoubleArray,
        maxAccelKmhPerS: Double = DEFAULT_MAX_ACCEL_KMH_PER_S,
    ): DoubleArray {
        val n = rawKmh.size
        val out = rawKmh.copyOf()
        if (n == 0) return out
        var prevT = Double.NaN
        var prevV = Double.NaN
        for (i in 0 until n) {
            val t = gps[i].ticks / TICKS_PER_SEC
            val v = out[i]
            if (!prevT.isNaN() && !prevV.isNaN()) {
                val dt = maxOf(t - prevT, 0.05)
                val accel = abs(v - prevV) / dt
                // Only flag high-speed jumps — going 0 → 10 km/h in 1 s is a
                // plausible paddle stroke (2.8 m/s²). 5 → 35 km/h in 1 s is not.
                if (accel > maxAccelKmhPerS && v > 15.0) {
                    out[i] = Double.NaN
                    continue
                }
            }
            prevT = t
            prevV = v
        }
        return out
    }

    /**
     * Clip implausible glitches (>60 km/h on a pumpfoil is always a bad fix)
     * + NaN holes, linearly interpolate, then 5-sample rolling median.
     */
    fun smoothSpeedKmh(raw: DoubleArray): DoubleArray {
        val n = raw.size
        val clipped = DoubleArray(n) { i ->
            val v = raw[i]
            if (!v.isFinite() || v > 60.0) Double.NaN else v
        }
        linearInterpolateInPlace(clipped)
        return rollingMedian(clipped, 5)
    }

    /**
     * Fill `Double.NaN` holes by linear interpolation between the
     * surrounding finite samples. Leading/trailing NaN runs are filled
     * with the nearest finite value; all-NaN input becomes all-zero.
     */
    internal fun linearInterpolateInPlace(arr: DoubleArray) {
        val n = arr.size
        if (n == 0) return
        var lastIdx = -1
        var lastVal = 0.0
        for (i in 0 until n) {
            if (arr[i].isFinite()) {
                if (lastIdx >= 0 && i - lastIdx > 1) {
                    val span = (i - lastIdx).toDouble()
                    val v0 = lastVal
                    val v1 = arr[i]
                    for (k in (lastIdx + 1) until i) {
                        val t = (k - lastIdx).toDouble() / span
                        arr[k] = v0 + (v1 - v0) * t
                    }
                }
                lastIdx = i
                lastVal = arr[i]
            }
        }
        val firstIdx = (0 until n).firstOrNull { arr[it].isFinite() }
        if (firstIdx == null) {
            for (i in 0 until n) arr[i] = 0.0
            return
        }
        val firstVal = arr[firstIdx]
        for (i in 0 until firstIdx) arr[i] = firstVal
        var lv = 0.0
        for (i in 0 until n) {
            if (arr[i].isFinite()) lv = arr[i] else arr[i] = lv
        }
    }

    /**
     * Centred rolling-median. Dispatches between a copy-and-sort impl for
     * tiny windows and a TreeMap-multiset incremental impl for big windows
     * (e.g. the 60 s × 100 Hz = 6000-sample baseline used by the fusion's
     * nose-angle drift correction).
     *
     * Both impls pick `sorted[len/2]` so they agree on even-length windows.
     */
    fun rollingMedian(x: DoubleArray, window: Int): DoubleArray {
        val w = maxOf(window, 1)
        return if (x.size < 64 || w < 32) rollingMedianSimple(x, w) else rollingMedianFast(x, w)
    }

    internal fun rollingMedianSimple(x: DoubleArray, window: Int): DoubleArray {
        val n = x.size
        val w = maxOf(window, 1)
        val half = w / 2
        val out = DoubleArray(n)
        // Centred window at index i covers [i-half, i+half+1), so for even w
        // the range is `w + 1` wide — buffer one slot beyond `w` to handle it.
        val buf = DoubleArray(w + 1)
        for (i in 0 until n) {
            val lo = maxOf(i - half, 0)
            val hi = minOf(i + half + 1, n)
            val len = hi - lo
            for (k in 0 until len) buf[k] = x[lo + k]
            Arrays.sort(buf, 0, len)
            out[i] = buf[len / 2]
        }
        return out
    }

    /**
     * O(n · log w) rolling median via two TreeMap multisets. `lower` and
     * `upper` together hold the current window; we balance so lower has
     * ⌈total/2⌉ elements (odd total → median = lower.lastKey, even total
     * → median = upper.firstKey, matching `sorted[len/2]`).
     */
    internal fun rollingMedianFast(x: DoubleArray, window: Int): DoubleArray {
        val n = x.size
        if (n == 0) return DoubleArray(0)
        val w = maxOf(window, 1)
        val half = w / 2
        val out = DoubleArray(n)

        val lower = java.util.TreeMap<Double, Int>()
        val upper = java.util.TreeMap<Double, Int>()
        var lowerSize = 0
        var upperSize = 0

        fun addLower(v: Double) {
            lower.merge(v, 1) { a, b -> a + b }
            lowerSize++
        }
        fun addUpper(v: Double) {
            upper.merge(v, 1) { a, b -> a + b }
            upperSize++
        }
        fun removeLower(v: Double) {
            val c = lower[v]!!
            if (c == 1) lower.remove(v) else lower[v] = c - 1
            lowerSize--
        }
        fun removeUpper(v: Double) {
            val c = upper[v]!!
            if (c == 1) upper.remove(v) else upper[v] = c - 1
            upperSize--
        }
        fun balance() {
            while (lowerSize > upperSize + 1) {
                val v = lower.lastKey()
                removeLower(v); addUpper(v)
            }
            while (upperSize > lowerSize) {
                val v = upper.firstKey()
                removeUpper(v); addLower(v)
            }
        }
        fun insert(v: Double) {
            if (lowerSize == 0 || v <= lower.lastKey()) addLower(v) else addUpper(v)
            balance()
        }
        fun remove(v: Double) {
            if (lower.containsKey(v)) removeLower(v) else removeUpper(v)
            balance()
        }
        fun median(): Double {
            val total = lowerSize + upperSize
            return if (total % 2 == 1) lower.lastKey() else upper.firstKey()
        }

        // Centred window at i=0 covers x[0 .. min(half+1, n)).
        val initialHi = minOf(half + 1, n)
        for (k in 0 until initialHi) insert(x[k])
        out[0] = median()

        for (i in 1 until n) {
            val newR = i + half
            if (newR < n) insert(x[newR])
            val oldL = i - half - 1
            if (oldL >= 0) remove(x[oldL])
            out[i] = median()
        }
        return out
    }
}

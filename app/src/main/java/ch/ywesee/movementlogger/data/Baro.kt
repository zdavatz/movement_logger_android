package ch.ywesee.movementlogger.data

import java.util.Arrays

/**
 * Board height above water via GPS-anchored water reference + temperature
 * compensation — Kotlin port of `baro.rs`.
 *
 * Why we need this: a pure baro-to-height conversion would drift with the
 * semi-sealed enclosure's thermal expansion. We anchor "P at water level"
 * to the pressure recorded while the GPS reports near-zero speed
 * (stationary on the water before / between rides), then read height
 * deviations relative to that reference.
 */
object Baro {

    private const val KELVIN_OFFSET = 273.15

    /** Hypsometric coefficient near sea level — `dh ≈ −8434 × dP/P`, ~1 % to 100 m. */
    private const val HYPSOMETRIC_H = 8434.0
    private const val STATIONARY_THRESHOLD_KMH = 3.0

    /**
     * Compute height above water in metres, aligned 1:1 with `sensors`.
     * Falls back to a session-max-pressure reference when GPS is empty
     * or never stationary.
     *
     * `speedKmh` is expected to be the smoothed GPS speed aligned with
     * `gps` row-for-row.
     */
    fun heightAboveWaterM(
        sensors: List<SensorRow>,
        gps: List<GpsRow>,
        speedKmh: DoubleArray,
        baseTicks: Double,
    ): DoubleArray {
        val n = sensors.size
        if (n == 0) return DoubleArray(0)

        // Temperature compensation: P_tc = P × T_ref / T (Kelvin)
        val tk = DoubleArray(n) { i -> sensors[i].temperatureC + KELVIN_OFFSET }
        val sortedTk = tk.copyOf()
        Arrays.sort(sortedTk)
        val refK = sortedTk[sortedTk.size / 2]
        val pressTc = DoubleArray(n) { i -> sensors[i].pressureMb * (refK / tk[i]) }

        if (gps.isEmpty() || speedKmh.isEmpty()) {
            return fallbackHeightFromSessionMax(pressTc)
        }

        val sensSec = DoubleArray(n) { i -> (sensors[i].ticks - baseTicks) / GpsMath.TICKS_PER_SEC }
        val gpsSec = DoubleArray(gps.size) { i -> (gps[i].ticks - baseTicks) / GpsMath.TICKS_PER_SEC }

        // Step 1: TC'd pressure interpolated onto the GPS time grid.
        val pressAtGps = interpLinear(gpsSec, sensSec, pressTc)

        // Step 2: anchors where the rider is stationary.
        val anchorTimes = ArrayList<Double>()
        val anchorPress = ArrayList<Double>()
        for (i in speedKmh.indices) {
            if (speedKmh[i] < STATIONARY_THRESHOLD_KMH) {
                anchorTimes.add(gpsSec[i])
                anchorPress.add(pressAtGps[i])
            }
        }
        if (anchorTimes.isEmpty()) return fallbackHeightFromSessionMax(pressTc)

        val at = anchorTimes.toDoubleArray()
        val ap = anchorPress.toDoubleArray()
        val waterRefGps = interpLinear(gpsSec, at, ap)

        // Step 3: water reference projected back onto the sensor timeline.
        val waterRefSens = interpLinear(sensSec, gpsSec, waterRefGps)

        // Step 4: height = 8434 × (1 − P_tc / P_ref)
        return DoubleArray(n) { i -> HYPSOMETRIC_H * (1.0 - pressTc[i] / waterRefSens[i]) }
    }

    private fun fallbackHeightFromSessionMax(pressTc: DoubleArray): DoubleArray {
        var pmax = Double.NEGATIVE_INFINITY
        for (p in pressTc) if (p > pmax) pmax = p
        return DoubleArray(pressTc.size) { i -> HYPSOMETRIC_H * (1.0 - pressTc[i] / pmax) }
    }

    /**
     * Linear interpolation of `y(x)` onto `xNew`. `x` must be ascending;
     * out-of-range queries clamp to edges. O(n + m) two-pointer walk.
     */
    fun interpLinear(xNew: DoubleArray, x: DoubleArray, y: DoubleArray): DoubleArray {
        require(x.size == y.size)
        val out = DoubleArray(xNew.size)
        if (x.isEmpty()) return out
        if (x.size == 1) {
            for (i in xNew.indices) out[i] = y[0]
            return out
        }
        val last = x.size - 1
        var j = 0
        for (i in xNew.indices) {
            val xn = xNew[i]
            if (xn <= x[0]) { out[i] = y[0]; continue }
            if (xn >= x[last]) { out[i] = y[last]; continue }
            while (j + 1 < x.size && x[j + 1] < xn) j++
            val x0 = x[j]; val x1 = x[j + 1]
            val t = if (x1 > x0) (xn - x0) / (x1 - x0) else 0.0
            out[i] = y[j] + (y[j + 1] - y[j]) * t
        }
        return out
    }
}

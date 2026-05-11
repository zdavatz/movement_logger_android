package ch.ywesee.movementlogger.data

/**
 * Slices the GPS + sensor parallel arrays to the absolute-UTC window
 * `[startMs, endMs]`. Anything outside the window is excluded, so the
 * export rendering only shows data that actually overlaps the rider video.
 *
 * `*AbsTimesMs` arrays must be sorted ascending (they are, since rows are
 * read in tick order). NaN/-1 entries are tolerated — they just fall
 * outside any sane window.
 */
object ReplayTrim {

    data class TrimmedSession(
        val gpsRows: List<GpsRow>,
        val gpsAbsTimesMs: LongArray,
        val speedSmoothedKmh: DoubleArray,
        val sensorAbsTimesMs: LongArray,
        val pitchDeg: DoubleArray,
        val baroHeightM: DoubleArray,
        val fusedHeightM: DoubleArray,
    )

    fun trimToWindow(
        gpsRows: List<GpsRow>,
        gpsAbsTimesMs: LongArray,
        speedSmoothedKmh: DoubleArray,
        sensorAbsTimesMs: LongArray,
        pitchDeg: DoubleArray,
        baroHeightM: DoubleArray,
        fusedHeightM: DoubleArray,
        startMs: Long,
        endMs: Long,
    ): TrimmedSession {
        val (g0, g1) = boundsInclusive(gpsAbsTimesMs, startMs, endMs)
        val (s0, s1) = boundsInclusive(sensorAbsTimesMs, startMs, endMs)

        val gpsRowsT = if (g0 <= g1) gpsRows.subList(g0, g1 + 1) else emptyList()
        val gpsTimesT = sliceLong(gpsAbsTimesMs, g0, g1)
        val speedT = sliceDouble(speedSmoothedKmh, g0, g1)
        val sensorTimesT = sliceLong(sensorAbsTimesMs, s0, s1)
        val pitchT = sliceDouble(pitchDeg, s0, s1)
        val baroT = sliceDouble(baroHeightM, s0, s1)
        val fusedT = sliceDouble(fusedHeightM, s0, s1)
        return TrimmedSession(gpsRowsT, gpsTimesT, speedT, sensorTimesT, pitchT, baroT, fusedT)
    }

    /** First and last indices (inclusive) of `arr` in `[lo, hi]`. Returns (-1, -2) when empty. */
    private fun boundsInclusive(arr: LongArray, lo: Long, hi: Long): Pair<Int, Int> {
        if (arr.isEmpty()) return -1 to -2
        var first = -1
        var last = -2
        for (i in arr.indices) {
            val t = arr[i]
            if (t < 0L) continue
            if (t in lo..hi) {
                if (first == -1) first = i
                last = i
            } else if (t > hi) break
        }
        return first to last
    }

    private fun sliceLong(arr: LongArray, lo: Int, hi: Int): LongArray {
        if (lo > hi || lo < 0) return LongArray(0)
        return arr.copyOfRange(lo, hi + 1)
    }

    private fun sliceDouble(arr: DoubleArray, lo: Int, hi: Int): DoubleArray {
        if (lo > hi || lo < 0 || lo >= arr.size) return DoubleArray(0)
        val end = (hi + 1).coerceAtMost(arr.size)
        return arr.copyOfRange(lo, end)
    }
}

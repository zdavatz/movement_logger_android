package ch.ywesee.movementlogger.data

/**
 * Shared tick → wall-clock mapping through the firmware's host-clock
 * `# SYNC` anchors. Extracted from `ReplayViewModel` so the Merge screen
 * can align multiple clips against the same session CSVs.
 */
object SyncTimeAlign {

    /**
     * Map row ticks → absolute epoch ms through the firmware's host-clock
     * `# SYNC` anchors. Piecewise-linear between anchors (drift-free when
     * several connects produced markers across the session); constant
     * 10 ms/tick extrapolation before the first / after the last anchor.
     * A single anchor degenerates to a fixed 10 ms/tick offset from that one
     * connect. `anchors` must be tick-sorted + deduped (as
     * [CsvParsers.parseSyncAnchors] returns). Mirrors the iOS
     * `ReplayViewModel.absTimesFromSyncAnchors`.
     */
    fun absTimesFromSyncAnchors(ticks: DoubleArray, anchors: List<SyncAnchor>): LongArray {
        val n = ticks.size
        if (n == 0 || anchors.isEmpty()) return LongArray(0)
        val first = anchors.first()
        val last = anchors.last()
        val out = LongArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = ticks[i]
            out[i] = when {
                t <= first.ticks -> first.epochMs + ((t - first.ticks) * 10.0).toLong()
                t >= last.ticks -> last.epochMs + ((t - last.ticks) * 10.0).toLong()
                else -> {
                    while (j + 1 < anchors.size && anchors[j + 1].ticks <= t) j++
                    val a = anchors[j]
                    val b = anchors[j + 1]
                    val frac = (t - a.ticks) / (b.ticks - a.ticks)
                    (a.epochMs + (b.epochMs - a.epochMs) * frac).toLong()
                }
            }
        }
        return out
    }
}

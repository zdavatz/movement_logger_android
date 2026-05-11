package ch.ywesee.movementlogger.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Quaternion → Euler conversion — Kotlin port of `euler.rs`. Hamilton
 * convention, ZYX order, quaternion layout [w, x, y, z].
 *
 * Pitch is clamped to ±90° in the gimbal-lock region; callers shade those
 * zones rather than trust the sudden roll/yaw flips that the math produces
 * there.
 */
object EulerAngles {

    /** (roll°, pitch°, yaw°) for one quaternion. */
    fun quatToEulerDeg(q: DoubleArray): Triple<Double, Double, Double> {
        require(q.size == 4)
        val qs = q[0]; val qi = q[1]; val qj = q[2]; val qk = q[3]

        val sinrCosp = 2.0 * (qs * qi + qj * qk)
        val cosrCosp = 1.0 - 2.0 * (qi * qi + qj * qj)
        val roll = Math.toDegrees(atan2(sinrCosp, cosrCosp))

        val sinp = 2.0 * (qs * qj - qk * qi)
        val pitch = if (abs(sinp) >= 1.0) {
            Math.toDegrees(sign(sinp) * PI / 2.0)
        } else {
            Math.toDegrees(asin(sinp))
        }

        val sinyCosp = 2.0 * (qs * qk + qi * qj)
        val cosyCosp = 1.0 - 2.0 * (qj * qj + qk * qk)
        val yaw = Math.toDegrees(atan2(sinyCosp, cosyCosp))

        return Triple(roll, pitch, yaw)
    }

    /** Vectorised: returns parallel (roll[], pitch[], yaw[]) arrays. */
    fun quatsToEulerDeg(quats: List<DoubleArray>): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = quats.size
        val roll = DoubleArray(n); val pitch = DoubleArray(n); val yaw = DoubleArray(n)
        for (i in 0 until n) {
            val (r, p, y) = quatToEulerDeg(quats[i])
            roll[i] = r; pitch[i] = p; yaw[i] = y
        }
        return Triple(roll, pitch, yaw)
    }

    /**
     * Contiguous gimbal-lock regions (|pitch| > 85°) as (start, end)
     * pairs where end is exclusive. Callers use this to shade those
     * zones red on Euler-angle plots.
     */
    fun gimbalLockRegions(pitch: DoubleArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var inRegion = false
        var start = 0
        for (i in pitch.indices) {
            val gl = abs(pitch[i]) > 85.0
            if (gl && !inRegion) { start = i; inRegion = true }
            else if (!gl && inRegion) { out.add(start until i); inRegion = false }
        }
        if (inRegion) out.add(start until pitch.size)
        return out
    }
}

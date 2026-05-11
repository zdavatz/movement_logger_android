package ch.ywesee.movementlogger.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Madgwick 6DOF AHRS — Kotlin port of `fusion.rs`. IMU-only (acc + gyro),
 * magnetometer deliberately skipped because the LIS2MDL drifts and
 * couples roll/pitch through the 9DOF gradient on this hardware.
 *
 * Quaternion layout is [w, x, y, z]. Input units: acc in mg (any unit
 * works, only direction matters), gyro in mdps (converted to rad/s
 * internally).
 */

private const val DEG_TO_RAD = PI / 180.0

class Madgwick(var beta: Double) {
    /** [w, x, y, z] — public for read; updated in place by [updateImu]. */
    val q: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0)

    /** Advance the filter by one sample. `dt` in seconds. */
    fun updateImu(gyroRad: DoubleArray, acc: DoubleArray, dt: Double) {
        require(gyroRad.size == 3 && acc.size == 3)

        var q0 = q[0]; var q1 = q[1]; var q2 = q[2]; var q3 = q[3]
        val gx = gyroRad[0]; val gy = gyroRad[1]; val gz = gyroRad[2]
        var ax = acc[0]; var ay = acc[1]; var az = acc[2]

        // Rate of change from gyro only
        var dQ0 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        var dQ1 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        var dQ2 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        var dQ3 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)

        // Accelerometer correction
        val aNorm = sqrt(ax * ax + ay * ay + az * az)
        if (aNorm > 1e-9) {
            ax /= aNorm; ay /= aNorm; az /= aNorm

            val _2q0 = 2.0 * q0
            val _2q1 = 2.0 * q1
            val _2q2 = 2.0 * q2
            val _2q3 = 2.0 * q3
            val _4q0 = 4.0 * q0
            val _4q1 = 4.0 * q1
            val _4q2 = 4.0 * q2
            val _8q1 = 8.0 * q1
            val _8q2 = 8.0 * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            val s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
            val s1 = _4q1 * q3q3 - _2q3 * ax + 4.0 * q0q0 * q1 - _2q0 * ay - _4q1 +
                _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
            val s2 = 4.0 * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 +
                _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
            val s3 = 4.0 * q1q1 * q3 - _2q1 * ax + 4.0 * q2q2 * q3 - _2q2 * ay

            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-9) {
                val inv = 1.0 / sNorm
                dQ0 -= beta * s0 * inv
                dQ1 -= beta * s1 * inv
                dQ2 -= beta * s2 * inv
                dQ3 -= beta * s3 * inv
            }
        }

        q0 += dQ0 * dt
        q1 += dQ1 * dt
        q2 += dQ2 * dt
        q3 += dQ3 * dt

        val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (n > 1e-9) {
            val inv = 1.0 / n
            q[0] = q0 * inv; q[1] = q1 * inv; q[2] = q2 * inv; q[3] = q3 * inv
        } else {
            q[0] = 1.0; q[1] = 0.0; q[2] = 0.0; q[3] = 0.0
        }
    }
}

object Fusion {

    /**
     * Run 6DOF fusion across all sensor samples. Returns one quaternion
     * (length-4 DoubleArray) per input row. Sample rate is auto-detected
     * from the tick delta.
     */
    fun computeQuaternions(samples: List<SensorRow>, beta: Double): List<DoubleArray> {
        if (samples.isEmpty()) return emptyList()
        val dt = detectDtSeconds(samples)
        val f = Madgwick(beta)
        val gyro = DoubleArray(3)
        val acc = DoubleArray(3)
        val out = ArrayList<DoubleArray>(samples.size)
        for (s in samples) {
            gyro[0] = s.gyroX * 0.001 * DEG_TO_RAD
            gyro[1] = s.gyroY * 0.001 * DEG_TO_RAD
            gyro[2] = s.gyroZ * 0.001 * DEG_TO_RAD
            acc[0] = s.accX; acc[1] = s.accY; acc[2] = s.accZ
            f.updateImu(gyro, acc, dt)
            out.add(f.q.copyOf())
        }
        return out
    }

    /**
     * Per-sample dt in seconds from the median tick delta. 1 tick = 10 ms,
     * so median 1 tick means 100 Hz sampling and dt = 0.01 s.
     */
    fun detectDtSeconds(samples: List<SensorRow>): Double {
        if (samples.size < 2) return 0.01
        val deltas = DoubleArray(samples.size - 1) { i -> samples[i + 1].ticks - samples[i].ticks }
        java.util.Arrays.sort(deltas)
        val median = deltas[deltas.size / 2]
        return (median * 0.01).coerceAtLeast(0.0001)
    }

    /**
     * Board nose elevation (°) from one quaternion. Sensor is mounted with
     * its Y-axis along the board nose direction (Breitachse); rotating
     * body-frame Y into world frame gives `nose_z = 2·(qj·qk − qs·qi)`.
     */
    fun noseZComponent(q: DoubleArray): Double {
        require(q.size == 4)
        val v = 2.0 * (q[2] * q[3] - q[0] * q[1])
        return v.coerceIn(-1.0, 1.0)
    }

    /**
     * Full nose-angle series in degrees, with 1-second median smoothing
     * and a 60-second rolling baseline subtraction (drift correction).
     */
    fun noseAngleSeriesDeg(quats: List<DoubleArray>, sampleHz: Int): DoubleArray {
        val n = quats.size
        val raw = DoubleArray(n) { i -> Math.toDegrees(asin(noseZComponent(quats[i]))) }
        val w1 = sampleHz.coerceAtLeast(1)
        val smoothed = GpsMath.rollingMedian(raw, w1)
        val w60 = 60 * sampleHz
        val baseline = GpsMath.rollingMedian(smoothed, w60)
        return DoubleArray(n) { i -> smoothed[i] - baseline[i] }
    }
}

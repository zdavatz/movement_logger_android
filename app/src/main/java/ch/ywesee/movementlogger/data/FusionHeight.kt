package ch.ywesee.movementlogger.data

/**
 * Complementary baro + accelerometer fusion for vertical position —
 * Kotlin port of `fusion_height.rs`.
 *
 * Pure baro after TC-correction has ~10-20 cm noise floor, so 1 Hz pump
 * strokes (5-15 cm amplitude) are at the edge of visibility. Integrated
 * world-frame vertical acc has sub-cm noise but drifts. Mixing the two
 * with an α-β filter gives short-term cleanliness of acc and long-term
 * absolute level of baro.
 *
 *     pos_pred = pos + vel·dt + 0.5·a·dt²
 *     vel_pred = vel + a·dt
 *     r        = baro_height − pos_pred
 *     pos      = pos_pred + α·r
 *     vel      = vel_pred + (β/dt)·r
 *
 * With α = 0.02, β = α²/2 the crossover is ~0.3 Hz: below baro dominates,
 * above (including 1 Hz pump) acc dominates.
 */
object FusionHeight {

    private const val GRAVITY_MS2 = 9.80665
    private const val MG_TO_MS2 = GRAVITY_MS2 / 1000.0

    fun fusedHeightM(
        sensors: List<SensorRow>,
        quats: List<DoubleArray>,
        baroHeight: DoubleArray,
        sampleHz: Double,
    ): DoubleArray {
        val n = sensors.size
        require(quats.size == n)
        require(baroHeight.size == n)
        if (n == 0) return DoubleArray(0)

        val dt = 1.0 / sampleHz
        val alpha = 0.02
        val beta = alpha * alpha * 0.5

        var pos = baroHeight[0]
        var vel = 0.0
        val out = DoubleArray(n)
        val aBody = DoubleArray(3)

        for (i in 0 until n) {
            aBody[0] = sensors[i].accX * MG_TO_MS2
            aBody[1] = sensors[i].accY * MG_TO_MS2
            aBody[2] = sensors[i].accZ * MG_TO_MS2

            val aWorld = rotateBodyToWorld(quats[i], aBody)
            val aUp = aWorld[2] - GRAVITY_MS2

            val posPred = pos + vel * dt + 0.5 * aUp * dt * dt
            val velPred = vel + aUp * dt
            val r = baroHeight[i] - posPred
            pos = posPred + alpha * r
            vel = velPred + (beta / dt) * r
            out[i] = pos
        }
        return out
    }

    /**
     * Rotate a body-frame vector into the world frame using a Madgwick
     * quaternion [w, x, y, z]. Standard q·v·q⁻¹ identity, written out.
     */
    fun rotateBodyToWorld(q: DoubleArray, v: DoubleArray): DoubleArray {
        require(q.size == 4 && v.size == 3)
        val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]
        val t0 = 2.0 * (qy * v[2] - qz * v[1])
        val t1 = 2.0 * (qz * v[0] - qx * v[2])
        val t2 = 2.0 * (qx * v[1] - qy * v[0])
        return doubleArrayOf(
            v[0] + qw * t0 + (qy * t2 - qz * t1),
            v[1] + qw * t1 + (qz * t0 - qx * t2),
            v[2] + qw * t2 + (qx * t1 - qy * t0),
        )
    }
}

package ch.ywesee.movementlogger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MathLayerTest {

    // ---- Butterworth -----------------------------------------------------

    @Test
    fun butterworthPassesDc() {
        val c = Butterworth.butter4Lowpass(cutoffHz = 5.0, fsHz = 100.0)
        // DC sums of b / a should be 1 by construction.
        val dcGain = c.b.sum() / c.a.sum()
        assertEquals(1.0, dcGain, 1e-9)
    }

    @Test
    fun butterworthAttenuatesHighFreq() {
        val fs = 100.0
        val cutoff = 2.0
        val c = Butterworth.butter4Lowpass(cutoff, fs)
        val n = 1024
        // 20 Hz sine (well above the 2 Hz cutoff)
        val high = DoubleArray(n) { i -> sin(2.0 * PI * 20.0 * i / fs) }
        val filtered = Butterworth.filtfilt(c, high)
        // Throw away the leading transient; the steady-state amplitude
        // should be tiny compared to the 1.0 input amplitude.
        val mid = filtered.copyOfRange(n / 2, n)
        val peak = mid.maxOf { abs(it) }
        assertTrue("expected strong attenuation, got peak=$peak", peak < 0.05)
    }

    @Test
    fun filtfiltKeepsSlowSignal() {
        val fs = 100.0
        val c = Butterworth.butter4Lowpass(2.0, fs)
        val n = 1024
        // 0.5 Hz sine — well below the 2 Hz cutoff
        val slow = DoubleArray(n) { i -> sin(2.0 * PI * 0.5 * i / fs) }
        val filtered = Butterworth.filtfilt(c, slow)
        val mid = filtered.copyOfRange(n / 4, 3 * n / 4)
        val peak = mid.maxOf { abs(it) }
        assertTrue("expected near-unity passthrough, got peak=$peak", peak > 0.9)
    }

    // ---- Euler -----------------------------------------------------------

    @Test
    fun identityQuaternionAllZero() {
        val (r, p, y) = EulerAngles.quatToEulerDeg(doubleArrayOf(1.0, 0.0, 0.0, 0.0))
        assertEquals(0.0, r, 1e-9)
        assertEquals(0.0, p, 1e-9)
        assertEquals(0.0, y, 1e-9)
    }

    @Test
    fun pitch90Detected() {
        // Quaternion for 90° rotation about Y-axis: [cos(45°), 0, sin(45°), 0]
        val a = PI / 4.0
        val q = doubleArrayOf(cos(a), 0.0, sin(a), 0.0)
        val (_, p, _) = EulerAngles.quatToEulerDeg(q)
        assertEquals(90.0, p, 1e-6)
    }

    @Test
    fun gimbalLockRegionsContiguous() {
        val pitches = doubleArrayOf(0.0, 0.0, 90.0, 89.0, 87.0, 0.0, 10.0, 90.0)
        val regs = EulerAngles.gimbalLockRegions(pitches)
        assertEquals(2, regs.size)
        assertEquals(2..4, regs[0])  // indices 2-4 are all > 85°
        assertEquals(7..7, regs[1])  // last index, kept open
    }

    // ---- Madgwick --------------------------------------------------------

    @Test
    fun stationaryGravityConvergesToIdentity() {
        // Hold the sensor flat (acc ≈ +Z gravity, no gyro). Quaternion
        // should converge near identity within a few hundred samples.
        val f = Madgwick(beta = 0.1)
        val gyro = doubleArrayOf(0.0, 0.0, 0.0)
        val accUp = doubleArrayOf(0.0, 0.0, 1.0)  // +Z up, unit length
        repeat(500) { f.updateImu(gyro, accUp, dt = 0.01) }
        // Identity check: w near 1, vector parts small
        assertEquals(1.0, f.q[0], 0.05)
        assertTrue(abs(f.q[1]) < 0.05)
        assertTrue(abs(f.q[2]) < 0.05)
        assertTrue(abs(f.q[3]) < 0.05)
    }

    @Test
    fun detectDtFrom100HzTicks() {
        // 100 Hz = 1 tick = 0.01 s per sample
        val sensors = (0 until 50).map { i ->
            SensorRow(
                ticks = (100 + i).toDouble(),
                accX = 0.0, accY = 0.0, accZ = 1000.0,
                gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0,
                magX = 0.0, magY = 0.0, magZ = 0.0,
                pressureMb = 1013.0, temperatureC = 20.0,
            )
        }
        val dt = Fusion.detectDtSeconds(sensors)
        assertEquals(0.01, dt, 1e-9)
    }

    @Test
    fun computeQuaternionsReturnsOnePerSample() {
        val sensors = (0 until 100).map { i ->
            SensorRow(
                ticks = (100 + i).toDouble(),
                accX = 0.0, accY = 0.0, accZ = 1000.0,
                gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0,
                magX = 0.0, magY = 0.0, magZ = 0.0,
                pressureMb = 1013.0, temperatureC = 20.0,
            )
        }
        val quats = Fusion.computeQuaternions(sensors, beta = 0.1)
        assertEquals(sensors.size, quats.size)
        // First and last should be unit-length
        val q0 = quats.first()
        val len0 = q0.fold(0.0) { acc, v -> acc + v * v }
        assertEquals(1.0, len0, 1e-9)
        val qN = quats.last()
        val lenN = qN.fold(0.0) { acc, v -> acc + v * v }
        assertEquals(1.0, lenN, 1e-9)
    }

    // ---- Baro / FusionHeight --------------------------------------------

    @Test
    fun interpLinearClampsAndInterpolates() {
        val x = doubleArrayOf(0.0, 1.0, 2.0)
        val y = doubleArrayOf(10.0, 20.0, 40.0)
        val q = doubleArrayOf(-1.0, 0.5, 1.5, 3.0)
        val out = Baro.interpLinear(q, x, y)
        assertEquals(10.0, out[0], 1e-9)  // clamped to left
        assertEquals(15.0, out[1], 1e-9)  // midpoint of 10..20
        assertEquals(30.0, out[2], 1e-9)  // midpoint of 20..40
        assertEquals(40.0, out[3], 1e-9)  // clamped to right
    }

    @Test
    fun baroFallbackWhenNoGps() {
        // Constant pressure → zero height everywhere (P/P_max = 1, so 8434·(1-1)=0).
        val sensors = (0 until 10).map { i ->
            SensorRow(
                ticks = i.toDouble(),
                accX = 0.0, accY = 0.0, accZ = 1000.0,
                gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0,
                magX = 0.0, magY = 0.0, magZ = 0.0,
                pressureMb = 1013.0, temperatureC = 20.0,
            )
        }
        val h = Baro.heightAboveWaterM(sensors, emptyList(), DoubleArray(0), baseTicks = 0.0)
        assertEquals(sensors.size, h.size)
        for (v in h) assertEquals(0.0, v, 1e-9)
    }

    @Test
    fun fusedHeightFollowsBaroAtRest() {
        // At rest with acc = +9.80665 m/s² up, motion acc cancels gravity exactly
        // → fused height follows baro.
        val n = 200
        val sensors = (0 until n).map { i ->
            SensorRow(
                ticks = i.toDouble(),
                accX = 0.0, accY = 0.0, accZ = 1000.0,  // exactly 1 g in mg
                gyroX = 0.0, gyroY = 0.0, gyroZ = 0.0,
                magX = 0.0, magY = 0.0, magZ = 0.0,
                pressureMb = 1013.0, temperatureC = 20.0,
            )
        }
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        val quats = List(n) { identity.copyOf() }
        // Step baro from 0 → 1 m at index 100
        val baro = DoubleArray(n) { i -> if (i < 100) 0.0 else 1.0 }
        val fused = FusionHeight.fusedHeightM(sensors, quats, baro, sampleHz = 100.0)
        // After enough settling time, fused should approach the baro step
        assertEquals(1.0, fused[n - 1], 0.2)
        assertEquals(0.0, fused[10], 0.1)
    }

    @Test
    fun rotateIdentityIsPassthrough() {
        val v = doubleArrayOf(1.0, 2.0, 3.0)
        val w = FusionHeight.rotateBodyToWorld(doubleArrayOf(1.0, 0.0, 0.0, 0.0), v)
        assertEquals(1.0, w[0], 1e-9)
        assertEquals(2.0, w[1], 1e-9)
        assertEquals(3.0, w[2], 1e-9)
    }

    @Test
    fun nonTrivialQuaternionRotates() {
        // 90° about Z should map (1,0,0) → (0,1,0)
        val a = PI / 4.0
        val q = doubleArrayOf(cos(a), 0.0, 0.0, sin(a))
        val w = FusionHeight.rotateBodyToWorld(q, doubleArrayOf(1.0, 0.0, 0.0))
        assertEquals(0.0, w[0], 1e-9)
        assertEquals(1.0, w[1], 1e-9)
        assertEquals(0.0, w[2], 1e-9)
        // Sanity: a real rotation shouldn't fall back to passthrough
        assertNotEquals(1.0, w[0], 1e-9)
    }
}

package ch.ywesee.movementlogger.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One decoded SensorStream snapshot. Mirrors the 46-byte packed layout from
 * the PumpLogger firmware (DESIGN.md §3 in fp-sns-stbox1), scaled into
 * convenient SI-ish units so the UI doesn't need to know the wire encoding.
 *
 * Authoritative spec: `stbox-viz-gui/src/ble.rs` LiveSample (desktop port).
 */
data class LiveSample(
    /** Box-local monotonic milliseconds since boot. Not wall-clock — box has no RTC. */
    val timestampMs: Long,
    /** Linear acceleration, mg per axis (LSM6DSV16X). */
    val accMg: ShortArray,
    /** Angular rate. Firmware ≤ v0.0.26 packed centi-dps (÷100 for °/s, but
     *  clamped at ±327 dps); v0.0.27+ packs DECI-dps (÷10 for °/s, full
     *  ±500 dps FS). Name kept for wire-parse stability; consumers divide by
     *  10. Only meaningful with matching firmware. */
    val gyroCdps: ShortArray,
    /** Magnetic field, milligauss per axis (LIS2MDL). */
    val magMg: ShortArray,
    /** Barometric pressure, raw Pa (LPS22DF). Divide by 100 for hPa. */
    val pressurePa: Int,
    /** Air temperature, 0.01 °C steps. Divide by 100 for °C. */
    val temperatureCc: Short,
    /** GPS latitude × 1e7. `0x7FFFFFFF` = no fix yet. */
    val gpsLatE7: Int,
    val gpsLonE7: Int,
    /** GPS altitude, signed metres. */
    val gpsAltM: Short,
    /** GPS speed, cm/h × 10 (~ km/h × 100). Divide by 100 for km/h. */
    val gpsSpeedCmh: Short,
    /** GPS course, centi-degrees (0..35999). */
    val gpsCourseCdeg: Short,
    /** Fix quality: 0 = no fix, 1 = GPS, … */
    val gpsFixQ: Int,
    /** Satellites used in the current fix. */
    val gpsNsat: Int,
    /** Strongest satellite C/N0 in dB-Hz (from GSV); 0 = no data. Antenna-quality metric. */
    val gpsCn0Max: Int,
    val gpsValid: Boolean,
    val loggingActive: Boolean,
    val lowBattery: Boolean,
) {
    /** Lat/lon in float degrees if the fix is valid, else null. */
    fun latLonDeg(): Pair<Double, Double>? =
        if (!gpsValid || gpsLatE7 == Int.MAX_VALUE) null
        else Pair(gpsLatE7 / 1.0e7, gpsLonE7 / 1.0e7)

    /** ‖acc‖ in g — used for the Live tab sparkline. */
    fun accMagnitudeG(): Double {
        val x = accMg[0] / 1000.0
        val y = accMg[1] / 1000.0
        val z = accMg[2] / 1000.0
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }

    /**
     * Roll φ (around the X axis), in degrees, derived from the gravity
     * component of accel. Meaningful only when net non-gravitational
     * acceleration is small. Range (-180, 180].
     */
    fun rollDeg(): Double {
        val ay = accMg[1].toDouble()
        val az = accMg[2].toDouble()
        return Math.toDegrees(kotlin.math.atan2(ay, az))
    }

    /** Pitch θ (around the Y axis), in degrees. Range [-90, 90]. */
    fun pitchDeg(): Double {
        val ax = accMg[0].toDouble()
        val ay = accMg[1].toDouble()
        val az = accMg[2].toDouble()
        return Math.toDegrees(
            kotlin.math.atan2(-ax, kotlin.math.sqrt(ay * ay + az * az))
        )
    }

    /**
     * Tilt-compensated compass heading ψ (yaw), in degrees, normalized to
     * [0, 360). Uses accel-derived roll/pitch to project the mag vector
     * onto the horizontal plane before taking atan2. Standard formula —
     * see e.g. ST AN4248.
     */
    fun headingDeg(magOffMg: FloatArray? = null): Double {
        val ax = accMg[0].toDouble()
        val ay = accMg[1].toDouble()
        val az = accMg[2].toDouble()
        // Hard-iron correction (Live tab "Calibrate compass"): a box-fixed
        // magnetic bias bigger than the ~200 mG horizontal earth field
        // otherwise pins the heading regardless of rotation.
        val mx = magMg[0].toDouble() - (magOffMg?.get(0)?.toDouble() ?: 0.0)
        val my = magMg[1].toDouble() - (magOffMg?.get(1)?.toDouble() ?: 0.0)
        val mz = magMg[2].toDouble() - (magOffMg?.get(2)?.toDouble() ?: 0.0)
        val roll = kotlin.math.atan2(ay, az)
        val pitch = kotlin.math.atan2(-ax, kotlin.math.sqrt(ay * ay + az * az))
        val sR = kotlin.math.sin(roll); val cR = kotlin.math.cos(roll)
        val sP = kotlin.math.sin(pitch); val cP = kotlin.math.cos(pitch)
        val mxH = mx * cP + my * sR * sP + mz * cR * sP
        val myH = my * cR - mz * sR
        var deg = Math.toDegrees(kotlin.math.atan2(-myH, mxH))
        if (deg < 0) deg += 360.0
        return deg
    }

    companion object {
        /** Wire-layout size, in bytes. */
        const val WIRE_SIZE = 46

        /** Decode the 46-byte little-endian wire layout. Returns null on bad length. */
        fun parse(bytes: ByteArray): LiveSample? {
            if (bytes.size != WIRE_SIZE) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val ts = bb.getInt(0).toLong() and 0xFFFF_FFFFL
            val flags = bytes[44].toInt() and 0xFF
            return LiveSample(
                timestampMs = ts,
                accMg = shortArrayOf(bb.getShort(4), bb.getShort(6), bb.getShort(8)),
                gyroCdps = shortArrayOf(bb.getShort(10), bb.getShort(12), bb.getShort(14)),
                magMg = shortArrayOf(bb.getShort(16), bb.getShort(18), bb.getShort(20)),
                pressurePa = bb.getInt(22),
                temperatureCc = bb.getShort(26),
                gpsLatE7 = bb.getInt(28),
                gpsLonE7 = bb.getInt(32),
                gpsAltM = bb.getShort(36),
                gpsSpeedCmh = bb.getShort(38),
                gpsCourseCdeg = bb.getShort(40),
                gpsFixQ = bytes[42].toInt() and 0xFF,
                gpsNsat = bytes[43].toInt() and 0xFF,
                gpsCn0Max = bytes[45].toInt() and 0xFF,
                gpsValid = flags and 0x01 != 0,
                lowBattery = flags and 0x02 != 0,
                loggingActive = flags and 0x04 != 0,
            )
        }
    }
}

/**
 * Body-frame world axes (n, e, d) — the render rows, produced by the
 * gyro+accel [OrientationFilter] below instead of the magnetometer.
 *
 * Kotlin port of the iOS `struct OriRows` (BLE/LiveSample.swift).
 */
class OriRows(val n: DoubleArray, val e: DoubleArray, val d: DoubleArray)

/**
 * Full 3D attitude from the two measured reference vectors — gravity (accel)
 * and the earth's magnetic field (mag) — via the classic TRIAD construction:
 * down = -acc, east = down × mag, north = east × down.
 *
 * Kotlin port of the iOS `enum Triad`. Used ONLY to SEED the gyro filter's
 * initial heading (so the preview doesn't start at a random yaw) and for the
 * render bias rotation ([world] / [noseAzimuth]); the live preview is
 * otherwise pure gyro+accel — no magnetometer in the render path.
 */
object Triad {
    /**
     * Body-frame unit rows (north, east, down), or null when degenerate
     * (zero vectors / field parallel to gravity).
     *
     * The LIS2MDL's Y axis is mirrored relative to the IMU frame on this
     * board (ST "esu" vs "enu" mounting) — a fixed hardware fact, always
     * applied, NOT user-configurable.
     */
    fun rows(acc: ShortArray, mag: ShortArray, off: DoubleArray?): OriRows? {
        val a = doubleArrayOf(acc[0].toDouble(), acc[1].toDouble(), acc[2].toDouble())
        val an = norm(a)
        if (an <= 100) return null                     // < 0.1 g: free-fall/garbage
        val d = doubleArrayOf(-a[0] / an, -a[1] / an, -a[2] / an)   // accel reads +1g UP
        val o = off ?: doubleArrayOf(0.0, 0.0, 0.0)
        val m = doubleArrayOf(
            mag[0].toDouble() - o[0],
            -(mag[1].toDouble() - o[1]),               // fixed mag-Y chirality flip
            mag[2].toDouble() - o[2],
        )
        val mn = norm(m)
        if (mn <= 20) return null                       // essentially no field signal
        val mu = doubleArrayOf(m[0] / mn, m[1] / mn, m[2] / mn)
        var e = cross(d, mu)
        val en = norm(e)
        if (en <= 0.05) return null                     // field ~parallel to gravity
        e = doubleArrayOf(e[0] / en, e[1] / en, e[2] / en)
        val n = cross(e, d)                             // unit by construction
        return OriRows(n, e, d)
    }

    /**
     * World (north, east, down) coordinates of a body-frame point, with the
     * vertical-axis bias rotation applied (the "USB-C points SOUTH" tap).
     * Returns [n, e, d].
     */
    fun world(p: DoubleArray, rows: OriRows, biasDeg: Double): DoubleArray {
        val n0 = dot(rows.n, p)
        val e0 = dot(rows.e, p)
        val d0 = dot(rows.d, p)
        val b = Math.toRadians(biasDeg)
        // Rotate the world frame by -bias: azimuths shrink by bias.
        val n1 = n0 * cos(b) + e0 * sin(b)
        val e1 = -n0 * sin(b) + e0 * cos(b)
        return doubleArrayOf(n1, e1, d0)
    }

    /**
     * Compass azimuth (deg, [0,360)) that the nose end currently points to,
     * bias applied.
     */
    fun noseAzimuth(rows: OriRows, nosePlusY: Boolean, biasDeg: Double): Double {
        val nose = doubleArrayOf(0.0, if (nosePlusY) 1.0 else -1.0, 0.0)
        val w = world(nose, rows, biasDeg)
        var az = Math.toDegrees(atan2(w[1], w[0]))
        if (az < 0) az += 360.0
        return az
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun norm(a: DoubleArray): Double =
        sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
}

/**
 * Accel + gyro complementary filter with drone-style gyro-bias
 * auto-calibration — the live 3D preview's attitude source. Kotlin port of
 * the iOS `final class OrientationFilter`.
 *
 * Why not the magnetometer: this box's hard-iron is so severe the computed
 * flat heading reads ≈ south regardless of orientation. The gyro measures the
 * box's actual rotation directly:
 *   • DOWN (tilt) comes from the accelerometer — exact, drift-free, every
 *     frame, so which face is up is always right in every pose.
 *   • the horizontal frame is carried by the gyroscope — real rotation
 *     (blue lid, on-end, backflip all consistent).
 *   • gyro bias (the slow-yaw-drift culprit) is auto-measured whenever the
 *     box rests still (drone pre-flight gyro cal) and subtracted.
 *   • absolute heading is supplied once by the "USB-C south" tap (a render
 *     bias); it drifts only slowly.
 */
class OrientationFilter {
    private var n = doubleArrayOf(1.0, 0.0, 0.0)     // body-frame world-North
    private var e = doubleArrayOf(0.0, 1.0, 0.0)     // body-frame world-East
    private var d = doubleArrayOf(0.0, 0.0, -1.0)    // body-frame world-Down (flat lid-up)
    private var gbias = doubleArrayOf(0.0, 0.0, 0.0) // gyro bias, deci-dps
    private var lastTick: Long? = null
    private var inited = false

    /** Latest body-frame world axes, or null until the filter has seeded. */
    val rows: OriRows? get() = if (inited) OriRows(n.copyOf(), e.copyOf(), d.copyOf()) else null
    val isReady: Boolean get() = inited

    /** Re-seed the attitude on the next sample; keep the learned gyro bias. */
    fun reset() { inited = false; lastTick = null }

    fun update(s: LiveSample, magOffset: DoubleArray?) {
        val acc = doubleArrayOf(s.accMg[0].toDouble(), s.accMg[1].toDouble(), s.accMg[2].toDouble())
        val aMag = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2])
        if (aMag <= 100) return

        // gyroCdps carries DECI-dps from firmware v0.0.27+ (÷10 for dps) —
        // the wider scale that no longer clamps a fast rotation at 327°/s.
        val gRaw = doubleArrayOf(
            s.gyroCdps[0].toDouble(), s.gyroCdps[1].toDouble(), s.gyroCdps[2].toDouble())
        var gCorr = doubleArrayOf(gRaw[0] - gbias[0], gRaw[1] - gbias[1], gRaw[2] - gbias[2])

        // Drone-style gyro-bias cal: while the box rests (tiny corrected rate
        // AND ~1 g), the raw gyro reading IS the bias — ease toward it.
        // 25 deci-dps = 2.5 dps threshold for "still".
        val gMag = sqrt(gCorr[0] * gCorr[0] + gCorr[1] * gCorr[1] + gCorr[2] * gCorr[2])
        if (gMag < 25 && aMag > 900 && aMag < 1100) {
            val k = 0.02
            for (i in 0..2) gbias[i] = gbias[i] * (1 - k) + gRaw[i] * k
            gCorr = doubleArrayOf(gRaw[0] - gbias[0], gRaw[1] - gbias[1], gRaw[2] - gbias[2])
        }

        val dt: Double = lastTick?.let {
            // Box timestamp is monotonic u32 ms; the delta is already
            // non-negative for our purposes (a reboot clamps via coerceIn).
            ((s.timestampMs - it) / 1000.0).coerceIn(0.005, 0.5)
        } ?: 0.1
        lastTick = s.timestampMs

        // Seed the frame from accel + a mag-derived heading (once), so the
        // preview doesn't start at a random yaw; the gyro owns it after.
        if (!inited) {
            val r = Triad.rows(s.accMg, s.magMg, magOffset)
            if (r != null) { n = r.n; e = r.e; d = r.d; inited = true }
            return
        }

        // Gyro propagation. A world-fixed vector expressed in the body frame
        // rotates by the body's inverse rotation over dt. Use the EXACT
        // rotation (Rodrigues) by angle -|ω|·dt about ω̂ — not the first-order
        // n - ω×n·dt, which loses accuracy badly for large per-sample angles.
        val w = doubleArrayOf(
            gCorr[0] * (Math.PI / 1800.0),    // deci-dps → rad/s (÷10 ·π/180)
            gCorr[1] * (Math.PI / 1800.0),
            gCorr[2] * (Math.PI / 1800.0),
        )
        val wMag = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        if (wMag > 1e-9) {
            val ang = -wMag * dt
            val axis = doubleArrayOf(w[0] / wMag, w[1] / wMag, w[2] / wMag)
            n = rotate(n, axis, ang)
            d = rotate(d, axis, ang)
        }

        // Tilt correction: nudge DOWN toward measured gravity when the accel
        // is trustworthy (near 1 g). Rate-independent gain (∝ dt, τ ≈ 0.6 s).
        if (aMag > 800 && aMag < 1200) {
            val inv = -1.0 / aMag
            val dMeas = doubleArrayOf(acc[0] * inv, acc[1] * inv, acc[2] * inv)
            val k = min(dt / 0.6, 0.15)
            for (i in 0..2) d[i] = d[i] * (1 - k) + dMeas[i] * k
        }

        // Re-orthonormalise: D, then N ⟂ D, then E = D × N (NED: D×N = E).
        d = normalized(d)
        val nd = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
        n = normalized(doubleArrayOf(n[0] - nd * d[0], n[1] - nd * d[1], n[2] - nd * d[2]))
        e = cross(d, n)

        // NO magnetometer heading re-anchor — heading is pure gyro (seeded
        // once from the mag at init, carried by the gyro with bias auto-
        // removed at rest). The user sets the absolute direction with
        // "USB-C south"; it holds and drifts only slowly.
    }

    /** Nose-end compass azimuth (deg, [0,360)) from the filter, bias applied. */
    fun noseAzimuth(nosePlusY: Boolean, biasDeg: Double): Double? {
        if (!inited) return null
        return Triad.noseAzimuth(OriRows(n, e, d), nosePlusY, biasDeg)
    }

    /** Rodrigues rotation of `v` about unit axis `k` by angle `a` (rad). */
    private fun rotate(v: DoubleArray, k: DoubleArray, a: Double): DoubleArray {
        val ca = cos(a); val sa = sin(a)
        val kv = cross(k, v)
        val kd = k[0] * v[0] + k[1] * v[1] + k[2] * v[2]
        val f = kd * (1 - ca)
        return normalized(doubleArrayOf(
            v[0] * ca + kv[0] * sa + k[0] * f,
            v[1] * ca + kv[1] * sa + k[1] * f,
            v[2] * ca + kv[2] * sa + k[2] * f,
        ))
    }

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalized(v: DoubleArray): DoubleArray {
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (m > 1e-9) doubleArrayOf(v[0] / m, v[1] / m, v[2] / m) else v
    }
}

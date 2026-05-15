package ch.ywesee.movementlogger.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    /** Angular rate, centi-dps per axis (raw_LSB × 1.75, ±327.67 dps). Divide by 100 for °/s. */
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
    fun headingDeg(): Double {
        val ax = accMg[0].toDouble()
        val ay = accMg[1].toDouble()
        val az = accMg[2].toDouble()
        val mx = magMg[0].toDouble()
        val my = magMg[1].toDouble()
        val mz = magMg[2].toDouble()
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
                gpsValid = flags and 0x01 != 0,
                lowBattery = flags and 0x02 != 0,
                loggingActive = flags and 0x04 != 0,
            )
        }
    }
}

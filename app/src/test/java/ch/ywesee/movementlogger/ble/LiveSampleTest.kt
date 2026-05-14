package ch.ywesee.movementlogger.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Round-trip test of the 46-byte SensorStream wire layout against a
 * hand-built packet. Mirrors the offsets in `stbox-viz-gui/src/ble.rs`
 * LiveSample::parse — keep this in sync with the desktop client.
 */
class LiveSampleTest {

    private fun build(
        timestampMs: Long = 1_234_567L,
        accMg: ShortArray = shortArrayOf(100, -200, 1000),
        gyroCdps: ShortArray = shortArrayOf(50, -75, 0),
        magMg: ShortArray = shortArrayOf(-300, 0, 450),
        pressurePa: Int = 101_325,
        temperatureCc: Short = 2_345,
        gpsLatE7: Int = 472_500_000,
        gpsLonE7: Int = 85_000_000,
        gpsAltM: Short = 412,
        gpsSpeedCmh: Short = 1_540,
        gpsCourseCdeg: Short = 12_345,
        gpsFixQ: Int = 1,
        gpsNsat: Int = 9,
        gpsValid: Boolean = true,
        lowBattery: Boolean = false,
        loggingActive: Boolean = true,
    ): ByteArray {
        val buf = ByteArray(46)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, timestampMs.toInt())
        bb.putShort(4, accMg[0]); bb.putShort(6, accMg[1]); bb.putShort(8, accMg[2])
        bb.putShort(10, gyroCdps[0]); bb.putShort(12, gyroCdps[1]); bb.putShort(14, gyroCdps[2])
        bb.putShort(16, magMg[0]); bb.putShort(18, magMg[1]); bb.putShort(20, magMg[2])
        bb.putInt(22, pressurePa)
        bb.putShort(26, temperatureCc)
        bb.putInt(28, gpsLatE7)
        bb.putInt(32, gpsLonE7)
        bb.putShort(36, gpsAltM)
        bb.putShort(38, gpsSpeedCmh)
        bb.putShort(40, gpsCourseCdeg)
        buf[42] = gpsFixQ.toByte()
        buf[43] = gpsNsat.toByte()
        var flags = 0
        if (gpsValid) flags = flags or 0x01
        if (lowBattery) flags = flags or 0x02
        if (loggingActive) flags = flags or 0x04
        buf[44] = flags.toByte()
        buf[45] = 0
        return buf
    }

    @Test fun `parse round-trips a representative packet`() {
        val s = LiveSample.parse(build())!!
        assertEquals(1_234_567L, s.timestampMs)
        assertEquals(100, s.accMg[0].toInt())
        assertEquals(-200, s.accMg[1].toInt())
        assertEquals(1000, s.accMg[2].toInt())
        assertEquals(50, s.gyroCdps[0].toInt())
        assertEquals(-75, s.gyroCdps[1].toInt())
        assertEquals(-300, s.magMg[0].toInt())
        assertEquals(450, s.magMg[2].toInt())
        assertEquals(101_325, s.pressurePa)
        assertEquals(2_345, s.temperatureCc.toInt())
        assertEquals(472_500_000, s.gpsLatE7)
        assertEquals(85_000_000, s.gpsLonE7)
        assertEquals(412, s.gpsAltM.toInt())
        assertEquals(1_540, s.gpsSpeedCmh.toInt())
        assertEquals(12_345, s.gpsCourseCdeg.toInt())
        assertEquals(1, s.gpsFixQ)
        assertEquals(9, s.gpsNsat)
        assertTrue(s.gpsValid)
        assertFalse(s.lowBattery)
        assertTrue(s.loggingActive)
    }

    @Test fun `parse rejects wrong-length packet`() {
        assertNull(LiveSample.parse(ByteArray(45)))
        assertNull(LiveSample.parse(ByteArray(47)))
        assertNull(LiveSample.parse(ByteArray(0)))
    }

    @Test fun `latLonDeg returns null when fix invalid`() {
        val s = LiveSample.parse(build(gpsValid = false))!!
        assertNull(s.latLonDeg())
    }

    @Test fun `latLonDeg returns null when lat sentinel set`() {
        val s = LiveSample.parse(build(gpsLatE7 = Int.MAX_VALUE))!!
        assertNull(s.latLonDeg())
    }

    @Test fun `latLonDeg scales by 1e7`() {
        val s = LiveSample.parse(build())!!
        val ll = s.latLonDeg()
        assertNotNull(ll)
        assertEquals(47.25, ll!!.first, 1e-9)
        assertEquals(8.5, ll.second, 1e-9)
    }

    @Test fun `accMagnitude in g`() {
        // 100, -200, 1000 mg → 0.1, -0.2, 1.0 g → sqrt(0.01+0.04+1) ≈ 1.0247
        val s = LiveSample.parse(build())!!
        assertEquals(1.0247, s.accMagnitudeG(), 1e-4)
    }
}

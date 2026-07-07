package ch.ywesee.movementlogger.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip test of the 32-byte CAL_GET / CAL_SET blob against the desktop
 * reference (`stbox-viz-gui/src/calibration.rs` tests). Keep this in sync
 * with the desktop client — the box merges per-field on any host's push, so
 * a wire-format skew is a silent data corruption bug on the box.
 */
class CalibrationTest {

    @Test
    fun roundtripAllFields() {
        val input = Calibration.EncodeInput(
            nosePlusY = true,
            magOffsetMg = doubleArrayOf(100.0, -50.0, 12.0),
            angleZeroRef = doubleArrayOf(-1.5, 12.3, 45.7),
            angleZeroAtEpochMs = 1_700_000_000_000L,
            headingBiasDeg = 90.4,
        )
        val blob = Calibration.encode(input)
        assertEquals(Calibration.BLOB_SIZE, blob.size)
        assertEquals(Calibration.LAYOUT_VERSION, blob[0])
        assertEquals(0x0F.toByte(), blob[1])  // all four bits
        val d = Calibration.decode(blob)!!
        assertEquals(true, d.nosePlusY)
        assertArrayEquals(doubleArrayOf(100.0, -50.0, 12.0), d.magOffsetMg, 0.0)
        // Tenths quantization: 12.3 stays exact, 45.7 stays exact.
        assertArrayEquals(doubleArrayOf(-1.5, 12.3, 45.7), d.angleZeroRef, 1e-9)
        assertEquals(1_700_000_000_000L, d.angleZeroAtEpochMs)
        assertEquals(90.4, d.headingBiasDeg!!, 1e-9)
    }

    @Test
    fun partialOnlySetsItsBit() {
        val input = Calibration.EncodeInput(nosePlusY = false)
        val blob = Calibration.encode(input)
        assertEquals(Calibration.MASK_NOSE_PLUS_Y.toByte(), blob[1])
        val d = Calibration.decode(blob)!!
        assertEquals(false, d.nosePlusY)
        assertNull(d.magOffsetMg)
        assertNull(d.angleZeroRef)
        assertNull(d.headingBiasDeg)
    }

    @Test
    fun emptyBlobIsUncalibrated() {
        // Version 0 → decode returns null (legacy firmware wouldn't reply at
        // all, so a version-0 blob means we misparsed).
        val bad = ByteArray(Calibration.BLOB_SIZE)
        assertNull(Calibration.decode(bad))
        // Fresh RAM blob from firmware v0.0.37+ has version=1 + mask=0.
        val fresh = ByteArray(Calibration.BLOB_SIZE)
        fresh[0] = Calibration.LAYOUT_VERSION
        val d = Calibration.decode(fresh)
        assertNotNull(d)
        assertNull(d!!.nosePlusY)
        assertNull(d.magOffsetMg)
        assertNull(d.angleZeroRef)
        assertNull(d.angleZeroAtEpochMs)
        assertNull(d.headingBiasDeg)
    }

    @Test
    fun angleZeroEpochZeroIsNoneSentinel() {
        // Bit set but epoch=0 → decode maps to null (the "never zeroed"
        // sentinel, per desktop calibration.rs).
        val input = Calibration.EncodeInput(
            angleZeroRef = doubleArrayOf(1.0, 2.0, 3.0),
            angleZeroAtEpochMs = null,
        )
        val blob = Calibration.encode(input)
        val d = Calibration.decode(blob)!!
        assertNotNull(d.angleZeroRef)
        assertNull(d.angleZeroAtEpochMs)
    }

    @Test
    fun shortBlobDecodesNull() {
        assertNull(Calibration.decode(ByteArray(31)))
        assertNull(Calibration.decode(ByteArray(0)))
    }
}

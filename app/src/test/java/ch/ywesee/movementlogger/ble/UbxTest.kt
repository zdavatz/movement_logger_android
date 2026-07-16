package ch.ywesee.movementlogger.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UbxTest {

    @Test
    fun checksumKnownVector() {
        // UBX-MON-VER poll: B5 62 0A 04 00 00 0E 34 (ck = 0x0E, 0x34).
        val (a, b) = ubxChecksum(byteArrayOf(0x0A, 0x04, 0x00, 0x00))
        assertEquals(0x0E, a)
        assertEquals(0x34, b)
    }

    @Test
    fun monSpanOffsets() {
        val pl = ByteArray(4 + 272)
        pl[0] = 0                                  // version
        pl[1] = 1                                  // numRfBlocks
        val o = 4
        pl[o + 100] = 200.toByte()                 // strongest bin at index 100
        putU32(pl, o + 256, 96_000_000L)           // span 96 MHz
        putU32(pl, o + 260, 375_000L)              // res 375 kHz
        putU32(pl, o + 264, 1_575_420_000L)        // center L1
        pl[o + 268] = 12                           // pga dB
        val v = parseMonSpan(pl)
        assertEquals(1, v.size)
        val blk = v[0]
        assertEquals(96_000_000L, blk.spanHz)
        assertEquals(375_000L, blk.resHz)
        assertEquals(1_575_420_000L, blk.centerHz)
        assertEquals(12, blk.pgaDb)
        val (pi, pa) = blk.peak()
        assertEquals(100, pi)
        assertEquals(200, pa)
        // bin 0 = center - span/2; bin 100 = that + 100*res
        val f0 = 1_575_420_000.0 - 48_000_000.0
        assertTrue(Math.abs(blk.binFreqHz(100) - (f0 + 100.0 * 375_000.0)) < 1e-6)
        // truncated payload → no blocks
        assertTrue(parseMonSpan(pl.copyOf(200)).isEmpty())
    }

    private fun putU32(p: ByteArray, o: Int, v: Long) {
        p[o] = (v and 0xFF).toByte()
        p[o + 1] = ((v shr 8) and 0xFF).toByte()
        p[o + 2] = ((v shr 16) and 0xFF).toByte()
        p[o + 3] = ((v shr 24) and 0xFF).toByte()
    }
}

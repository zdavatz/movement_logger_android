package ch.ywesee.movementlogger.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class UbloxGpsReaderTest {

    /**
     * Canonical UBX-CFG-RATE for 10 Hz, computed by hand against the
     * u-blox protocol spec:
     *   sync1 sync2 class id  len_lo len_hi  measLo measHi navLo navHi tRefLo tRefHi  CK_A CK_B
     *    B5    62   06   08    06     00      64     00     01    00     01     00     7A   12
     *
     * If the helper drifts (wrong field order, wrong endianness, wrong
     * Fletcher seed) this assertion catches it before we ever send the
     * frame to the receiver.
     */
    @Test
    fun ubxCfgRate_10hz_matchesCanonicalBytes() {
        val expected = byteArrayOf(
            0xB5.toByte(), 0x62.toByte(),
            0x06.toByte(), 0x08.toByte(),
            0x06.toByte(), 0x00.toByte(),
            0x64.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(),
            0x7A.toByte(), 0x12.toByte(),
        )
        val actual = UbloxGpsReader.buildUbxCfgRate(measRateMs = 100)
        assertArrayEquals(expected, actual)
    }
}

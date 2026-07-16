package ch.ywesee.movementlogger.ble

/**
 * u-blox UBX wire helpers, incremental frame parser, and NAV/MON decoders for
 * the GPS Debug survey ([BleGpsSurvey]). Port of the desktop
 * `gps-debug/src/survey.rs` UBX layer. All multi-byte fields are little-endian.
 */

// (class, id) of the messages we poll.
internal val NAV_PVT = 0x01 to 0x07
internal val NAV_DOP = 0x01 to 0x04
internal val NAV_SAT = 0x01 to 0x35
internal val NAV_SIG = 0x01 to 0x43
internal val MON_RF = 0x0A to 0x38
internal val MON_SPAN = 0x0A to 0x31
internal val POLLS = listOf(NAV_PVT, NAV_DOP, NAV_SAT, NAV_SIG, MON_RF, MON_SPAN)

/** 8-bit Fletcher checksum over class..payload-end (UBX spec). */
internal fun ubxChecksum(body: ByteArray): Pair<Int, Int> {
    var a = 0; var b = 0
    for (x in body) { a = (a + (x.toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF }
    return a to b
}

/** Build a poll request: target class/id with an empty payload. */
internal fun pollFrame(m: Pair<Int, Int>): ByteArray {
    val body = byteArrayOf(m.first.toByte(), m.second.toByte(), 0, 0)
    val (a, b) = ubxChecksum(body)
    return byteArrayOf(0xB5.toByte(), 0x62, m.first.toByte(), m.second.toByte(), 0, 0, a.toByte(), b.toByte())
}

internal class UbxFrame(val cls: Int, val id: Int, val payload: ByteArray)

/**
 * Incremental UBX frame extractor. Feed it raw bytes (across multiple BLE
 * notifies); it appends decoded frames to `out` as their checksums verify.
 * Non-UBX bytes (NMEA, noise) are skipped by the sync hunt.
 */
internal class UbxParser {
    private var state = 0
    private var cls = 0
    private var id = 0
    private var len = 0
    private var buf = ByteArray(0)
    private var got = 0
    private var ckA = 0
    private var ckB = 0

    fun feed(data: ByteArray, out: MutableList<UbxFrame>) {
        for (byte in data) push(byte.toInt() and 0xFF, out)
    }

    private fun push(b: Int, out: MutableList<UbxFrame>) {
        when (state) {
            0 -> if (b == 0xB5) state = 1
            // Saw 0xB5. 0x62 completes the sync word; a repeated 0xB5 keeps us
            // armed (previous byte was a false sync); anything else resets.
            1 -> state = if (b == 0x62) 2 else if (b == 0xB5) 1 else 0
            2 -> { cls = b; state = 3 }
            3 -> { id = b; state = 4 }
            4 -> { len = b; state = 5 }
            5 -> {
                len = len or (b shl 8)
                if (len > 4096) {                 // guard a corrupt length
                    state = 0
                } else {
                    buf = ByteArray(len); got = 0
                    state = if (len == 0) 7 else 6
                }
            }
            6 -> {
                buf[got++] = b.toByte()
                if (got >= len) state = 7
            }
            7 -> { ckA = b; state = 8 }
            8 -> {
                ckB = b
                val body = ByteArray(4 + len)
                body[0] = cls.toByte(); body[1] = id.toByte()
                body[2] = (len and 0xFF).toByte(); body[3] = ((len shr 8) and 0xFF).toByte()
                System.arraycopy(buf, 0, body, 4, len)
                val (a, c) = ubxChecksum(body)
                if (a == ckA && c == ckB) out.add(UbxFrame(cls, id, buf.copyOf()))
                state = 0
            }
        }
    }
}

// ---- little-endian field readers -------------------------------------------

private fun u8(p: ByteArray, o: Int): Int = p[o].toInt() and 0xFF
private fun u16(p: ByteArray, o: Int): Int = u8(p, o) or (u8(p, o + 1) shl 8)
private fun i16(p: ByteArray, o: Int): Int = u16(p, o).toShort().toInt()
private fun i32(p: ByteArray, o: Int): Int =
    u8(p, o) or (u8(p, o + 1) shl 8) or (u8(p, o + 2) shl 16) or (u8(p, o + 3) shl 24)
private fun u32(p: ByteArray, o: Int): Long = i32(p, o).toLong() and 0xFFFFFFFFL

// ---- decoded structures ----------------------------------------------------

internal data class NavPvt(
    var itow: Long = 0,
    var year: Int = 0, var month: Int = 0, var day: Int = 0,
    var hour: Int = 0, var min: Int = 0, var sec: Int = 0, var valid: Int = 0,
    var fixType: Int = 0, var gnssFixOk: Boolean = false, var numSv: Int = 0,
    var lonDeg: Double = 0.0, var latDeg: Double = 0.0, var heightM: Double = 0.0, var hmslM: Double = 0.0,
    var haccM: Double = 0.0, var vaccM: Double = 0.0, var saccMps: Double = 0.0, var pdop: Double = 0.0,
)
internal data class NavDop(var hdop: Double = 0.0, var vdop: Double = 0.0)
internal data class SatInfo(
    val gnss: Int, val sv: Int, val cno: Int, val elev: Int, val azim: Int,
    val prResM: Double, val qual: Int, val svUsed: Boolean,
)
internal data class SigInfo(
    val gnss: Int, val sv: Int, val sig: Int, val cno: Int,
    val prResM: Double, val qual: Int, val prUsed: Boolean,
)
internal data class MonRf(
    var antStatus: Int = 0, var antPower: Int = 0, var noisePerMs: Int = 0,
    var agcCnt: Int = 0, var jamInd: Int = 0, var jammingState: Int = 0,
)
/**
 * One RF block of a UBX-MON-SPAN reply — the receiver's coarse spectrum
 * snapshot: 256 amplitude bins (uncalibrated) covering [spanHz] around
 * [centerHz]. Single-band MAX-M10S reports one block (L1). Not supported on
 * M8 receivers — those simply never answer the poll.
 */
internal class SpanBlock(
    val spectrum: IntArray,
    val spanHz: Long, val resHz: Long, val centerHz: Long, val pgaDb: Int,
) {
    /** Frequency of bin [i] in Hz (bin 0 = center - span/2). */
    fun binFreqHz(i: Int): Double = centerHz.toDouble() - spanHz / 2.0 + i.toDouble() * resHz
    /** (bin index, amplitude) of the strongest bin. */
    fun peak(): Pair<Int, Int> {
        var pi = 0; var pa = 0
        for (i in spectrum.indices) if (spectrum[i] > pa) { pa = spectrum[i]; pi = i }
        return pi to pa
    }
}

internal data class Epoch(
    var pvt: NavPvt? = null,
    var dop: NavDop? = null,
    var sats: List<SatInfo> = emptyList(),
    var sigs: List<SigInfo> = emptyList(),
    var rf: MonRf? = null,
    var span: List<SpanBlock> = emptyList(),
)

internal fun parseNavPvt(p: ByteArray): NavPvt? {
    if (p.size < 78) return null
    return NavPvt(
        itow = u32(p, 0),
        year = u16(p, 4), month = u8(p, 6), day = u8(p, 7),
        hour = u8(p, 8), min = u8(p, 9), sec = u8(p, 10), valid = u8(p, 11),
        fixType = u8(p, 20), gnssFixOk = (u8(p, 21) and 0x01) != 0, numSv = u8(p, 23),
        lonDeg = i32(p, 24) * 1e-7, latDeg = i32(p, 28) * 1e-7,
        heightM = i32(p, 32) / 1000.0, hmslM = i32(p, 36) / 1000.0,
        haccM = u32(p, 40) / 1000.0, vaccM = u32(p, 44) / 1000.0, saccMps = u32(p, 68) / 1000.0,
        pdop = u16(p, 76) * 0.01,
    )
}

internal fun parseNavDop(p: ByteArray): NavDop? {
    if (p.size < 18) return null
    return NavDop(hdop = u16(p, 12) * 0.01, vdop = u16(p, 10) * 0.01)
}

internal fun parseNavSat(p: ByteArray): List<SatInfo> {
    val v = ArrayList<SatInfo>()
    if (p.size < 8) return v
    val n = u8(p, 5)
    for (i in 0 until n) {
        val o = 8 + i * 12
        if (o + 12 > p.size) break
        val flags = u32(p, o + 8)
        v.add(SatInfo(u8(p, o), u8(p, o + 1), u8(p, o + 2),
            p[o + 3].toInt(), i16(p, o + 4), i16(p, o + 6) * 0.1,
            (flags and 0x07L).toInt(), (flags and 0x08L) != 0L))
    }
    return v
}

internal fun parseNavSig(p: ByteArray): List<SigInfo> {
    val v = ArrayList<SigInfo>()
    if (p.size < 8) return v
    val n = u8(p, 5)
    for (i in 0 until n) {
        val o = 8 + i * 16
        if (o + 16 > p.size) break
        val sigFlags = u16(p, o + 10)
        v.add(SigInfo(u8(p, o), u8(p, o + 1), u8(p, o + 2), u8(p, o + 6),
            i16(p, o + 4) * 0.1, u8(p, o + 7), (sigFlags and 0x08) != 0))
    }
    return v
}

internal fun parseMonRf(p: ByteArray): MonRf? {
    if (p.size < 4) return null
    val n = u8(p, 1)
    if (n == 0 || p.size < 4 + 24) return null
    val o = 4   // first RF block (single-band MAX-M10S has one: L1)
    return MonRf(
        antStatus = u8(p, o + 2), antPower = u8(p, o + 3),
        noisePerMs = u16(p, o + 12), agcCnt = u16(p, o + 14),
        jamInd = u8(p, o + 16), jammingState = u8(p, o + 1) and 0x03,
    )
}

/**
 * UBX-MON-SPAN: version U1, numRfBlocks U1, reserved U1[2], then per block
 * spectrum U1[256] + span U4 + res U4 + center U4 + pga U1 + reserved U1[3]
 * (272 B/block).
 */
internal fun parseMonSpan(p: ByteArray): List<SpanBlock> {
    val v = ArrayList<SpanBlock>()
    if (p.size < 4) return v
    val n = u8(p, 1)
    for (i in 0 until n) {
        val o = 4 + i * 272
        if (o + 272 > p.size) break
        val spec = IntArray(256) { u8(p, o + it) }
        v.add(SpanBlock(spec, u32(p, o + 256), u32(p, o + 260), u32(p, o + 264), u8(p, o + 268)))
    }
    return v
}

// ---- enum → text decoders (match the desktop CSV strings) ------------------

internal fun gnssName(id: Int): String = when (id) {
    0 -> "GPS"; 1 -> "SBAS"; 2 -> "Galileo"; 3 -> "BeiDou"
    4 -> "IMES"; 5 -> "QZSS"; 6 -> "GLONASS"; 7 -> "NavIC"; else -> "?"
}
internal fun sigName(gnss: Int, sig: Int): String = when (gnss to sig) {
    0 to 0 -> "L1C/A"; 0 to 3 -> "L2CL"; 0 to 4 -> "L2CM"
    1 to 0 -> "L1C/A"
    2 to 0 -> "E1C"; 2 to 1 -> "E1B"; 2 to 5 -> "E5bI"; 2 to 6 -> "E5bQ"
    3 to 0 -> "B1ID1"; 3 to 1 -> "B1ID2"; 3 to 2 -> "B2ID1"; 3 to 3 -> "B2ID2"
    5 to 0 -> "L1C/A"; 5 to 1 -> "L1S"; 5 to 4 -> "L2CM"; 5 to 5 -> "L2CL"
    6 to 0 -> "L1OF"; 6 to 2 -> "L2OF"
    7 to 0 -> "L5A"
    else -> "sig?"
}
internal fun antStatusName(s: Int): String = when (s) {
    0 -> "INIT"; 1 -> "UNKNOWN"; 2 -> "OK"; 3 -> "SHORT"; 4 -> "OPEN"; else -> "?"
}
internal fun antPowerName(s: Int): String = when (s) {
    0 -> "OFF"; 1 -> "ON"; 2 -> "UNKNOWN"; else -> "?"
}
internal fun jammingName(s: Int): String = when (s) {
    0 -> "unknown"; 1 -> "ok"; 2 -> "warning"; 3 -> "critical"; else -> "?"
}

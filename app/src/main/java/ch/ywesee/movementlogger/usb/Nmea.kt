package ch.ywesee.movementlogger.usb

/**
 * Minimal NMEA-0183 parser for the u-blox GNSS receiver — handles the two
 * sentence types we care about for a live fix display:
 *
 *  - `$xxRMC` — Recommended Minimum: UTC time, status, lat, lon, speed
 *    (knots), course-over-ground, date.
 *  - `$xxGGA` — Fix data: UTC time, lat, lon, fix quality, sat count,
 *    HDOP, altitude (m).
 *
 * The talker prefix (`GP`, `GN`, `GL`, `GA`, `BD`, ...) is ignored — we
 * just key on the last three letters. u-blox emits `$GN…` when multiple
 * constellations have contributed to the fix; older single-system flows
 * emit `$GP…`.
 *
 * Returns parsed values verbatim from the NMEA wire (degrees decimal
 * with sign for lat/lon, km/h for speed, metres for altitude). Caller is
 * responsible for combining RMC + GGA into a single fix row.
 */

data class RmcFix(
    val utc: String,            // hhmmss.ss verbatim (empty if absent)
    val statusValid: Boolean,   // 'A' = active, 'V' = void
    val latDeg: Double?,        // signed decimal degrees (null on missing/bad)
    val lonDeg: Double?,
    val speedKmh: Double?,      // knots * 1.852
    val courseDeg: Double?,
    val dateDdmmyy: String,     // ddmmyy verbatim (empty if absent)
)

data class GgaFix(
    val utc: String,
    val latDeg: Double?,
    val lonDeg: Double?,
    val fixQuality: Int,        // 0 = no fix, 1 = GPS, 2 = DGPS, ...
    val numSat: Int,
    val hdop: Double?,
    val altM: Double?,
)

object Nmea {

    /**
     * Validate the trailing `*HH` checksum on a raw NMEA line. Returns
     * `null` if the line is malformed (missing `$`, missing `*`, bad hex)
     * or if the checksum doesn't match; otherwise the payload between
     * `$` and `*` (no leading `$`, no checksum).
     */
    fun verifyAndStrip(line: String): String? {
        val dollar = line.indexOf('$')
        if (dollar < 0) return null
        val star = line.indexOf('*', dollar + 1)
        if (star < 0 || star + 3 > line.length) return null
        val payload = line.substring(dollar + 1, star)
        val hex = line.substring(star + 1, star + 3)
        val expected = hex.toIntOrNull(16) ?: return null
        var c = 0
        for (i in payload.indices) c = c xor payload[i].code
        return if (c == expected) payload else null
    }

    fun parseRmc(payload: String): RmcFix? {
        val f = payload.split(',')
        // $GPRMC,hhmmss.ss,A,llll.ll,N,yyyyy.yy,W,sss.s,ccc.c,ddmmyy,...
        if (f.size < 10) return null
        if (!(f[0].endsWith("RMC"))) return null
        return RmcFix(
            utc = f[1],
            statusValid = f[2] == "A",
            latDeg = ddmmToDeg(f[3], f[4]),
            lonDeg = ddmmToDeg(f[5], f[6]),
            speedKmh = f[7].toDoubleOrNull()?.let { it * 1.852 },
            courseDeg = f[8].toDoubleOrNull(),
            dateDdmmyy = f[9],
        )
    }

    fun parseGga(payload: String): GgaFix? {
        val f = payload.split(',')
        // $GPGGA,hhmmss.ss,llll.ll,N,yyyyy.yy,W,q,nn,hh.h,aaa.a,M,...
        if (f.size < 10) return null
        if (!(f[0].endsWith("GGA"))) return null
        return GgaFix(
            utc = f[1],
            latDeg = ddmmToDeg(f[2], f[3]),
            lonDeg = ddmmToDeg(f[4], f[5]),
            fixQuality = f[6].toIntOrNull() ?: 0,
            numSat = f[7].toIntOrNull() ?: 0,
            hdop = f[8].toDoubleOrNull(),
            altM = f[9].toDoubleOrNull(),
        )
    }

    /**
     * Convert an NMEA dddmm.mmmm + hemisphere to signed decimal degrees.
     * Empty / unparseable inputs return null (caller treats this as "no
     * fix on that axis").
     */
    private fun ddmmToDeg(value: String, hemisphere: String): Double? {
        if (value.isEmpty()) return null
        val raw = value.toDoubleOrNull() ?: return null
        // Degrees portion = integer hundreds of `raw` (i.e. value / 100,
        // floored). Minutes portion = remainder.
        val deg = (raw / 100.0).toInt()
        val minutes = raw - deg * 100.0
        val signed = deg + minutes / 60.0
        return when (hemisphere) {
            "S", "W" -> -signed
            else -> signed
        }
    }
}

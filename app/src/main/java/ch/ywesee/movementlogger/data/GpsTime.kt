package ch.ywesee.movementlogger.data

import java.util.Calendar
import java.util.TimeZone

/**
 * GPS UTC strings are `hhmmss.ss` — wall-clock time-of-day with no date.
 * To turn them into absolute UTC millis we have to combine the time
 * with a date. The desktop project defaults to the file's mtime and
 * lets the user override with `--date YYYY-MM-DD`. On Android we don't
 * have a clean mtime for an SD-card recording downloaded via BLE, so
 * we fall back to "today" by default and surface the date as a tunable.
 */
object GpsTime {

    /**
     * Convert `hhmmss.ss` + a calendar date into absolute UTC millis.
     * Returns null when `utcStr` doesn't parse — the caller is
     * responsible for surfacing the failure (most likely the GPS had
     * no fix yet and emitted "0").
     */
    fun toUtcMillis(utcStr: String, dateYyyy: Int, dateMonth1to12: Int, dateDay: Int): Long? {
        val secs = parseHhmmssSs(utcStr) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(dateYyyy, dateMonth1to12 - 1, dateDay, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis + (secs * 1000.0).toLong()
    }

    /** "hhmmss.ss" → seconds-of-day; null when the string doesn't fit. */
    fun parseHhmmssSs(utcStr: String): Double? {
        val s = utcStr.trim()
        if (s.length < 6) return null
        val hh = s.substring(0, 2).toIntOrNull() ?: return null
        val mm = s.substring(2, 4).toIntOrNull() ?: return null
        val ssPart = s.substring(4)
        val ss = ssPart.toDoubleOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59 || ss < 0.0 || ss >= 60.0) return null
        return hh * 3600.0 + mm * 60.0 + ss
    }

    /** Today in UTC as (year, month1-12, day). */
    fun todayUtc(): Triple<Int, Int, Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return Triple(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }
}

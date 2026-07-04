package ch.ywesee.movementlogger.sync

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub-Releases client for the **box firmware** repo (not this app).
 *
 * Mirrors the desktop `update.rs::check_latest_firmware` / `download` /
 * `parse_version`: fetch the newest `vX.Y.Z` release whose assets include a
 * `firmware-v*.bin`, expose `{ version, downloadUrl }`, and download the image
 * as a `ByteArray`. Deliberately separate from the *app* self-updater so the
 * two update paths can never cross-wire (the app has no in-store self-update
 * on Android anyway).
 *
 * Uses `HttpURLConnection` (no OkHttp/Retrofit dependency in this project) and
 * `org.json` (bundled in the Android SDK). All calls are blocking — invoke from
 * a background coroutine/thread.
 */
object FirmwareRelease {

    private const val TAG = "FirmwareRelease"
    /** The firmware repo, NOT `movement_logger_android`. */
    private const val REPO = "zdavatz/movement_logger_firmware"
    private const val TAG_PREFIX = "v"
    private const val USER_AGENT = "MovementLogger-Android"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_TIMEOUT_MS = 120_000

    /** Newest firmware release + the direct download URL of its `.bin` asset. */
    data class Latest(val version: String, val downloadUrl: String)

    /**
     * Query GitHub for the newest firmware release: the highest `vX.Y.Z` tag
     * (non-prerelease) whose assets include a `firmware-v*.bin`. Returns the
     * bare `X.Y.Z` version + that asset's download URL, or `null` on any
     * network / parse failure or if no release ships the asset — the caller
     * treats `null` as "couldn't reach GitHub" (no banner).
     */
    fun fetchLatest(): Latest? {
        val body = httpGetString(
            "https://api.github.com/repos/$REPO/releases?per_page=30",
            accept = "application/vnd.github+json",
        ) ?: return null
        return try {
            val releases = JSONArray(body)
            var best: Triple<Int, Int, Int>? = null
            var bestRel: Latest? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("prerelease", false)) continue
                val tag = r.optString("tag_name", "")
                if (!tag.startsWith(TAG_PREFIX)) continue
                val stripped = tag.substring(TAG_PREFIX.length)
                val v = parseVersion(stripped) ?: continue
                val dl = firmwareAssetUrl(r.optJSONArray("assets")) ?: continue
                if (best == null || compareVersions(v, best) > 0) {
                    best = v
                    bestRel = Latest(stripped, dl)
                }
            }
            bestRel
        } catch (e: Exception) {
            Log.w(TAG, "parse releases failed: ${e.message}")
            null
        }
    }

    /** Blocking GET of the firmware image bytes; `null` on any failure. */
    fun download(url: String): ByteArray? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "download HTTP ${conn.responseCode}")
                    return null
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            null
        }
    }

    /** First asset named `firmware-v*.bin`, or null. */
    private fun firmwareAssetUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (name.startsWith("firmware-v") && name.endsWith(".bin")) {
                val url = a.optString("browser_download_url", "")
                if (url.isNotEmpty()) return url
            }
        }
        return null
    }

    /**
     * Semver-ish parse of "X.Y.Z" → Triple(major, minor, patch). Trailing
     * non-digits on the patch component are tolerated ("0.0.3-rc" → 0,0,3),
     * matching the desktop `parse_version`. Returns null if any of the three
     * numeric components is missing/non-numeric.
     */
    fun parseVersion(s: String): Triple<Int, Int, Int>? {
        val parts = s.split('.', limit = 3)
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patchDigits = parts[2].takeWhile { it.isDigit() }
        val patch = patchDigits.toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }

    /** Component-wise compare; `>0` means `a` is newer than `b`. */
    fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first - b.first
        if (a.second != b.second) return a.second - b.second
        return a.third - b.third
    }

    private fun httpGetString(url: String, accept: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "GET $url → HTTP ${conn.responseCode}")
                    return null
                }
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }
    }
}

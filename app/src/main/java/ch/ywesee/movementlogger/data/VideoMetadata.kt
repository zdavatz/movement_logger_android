package ch.ywesee.movementlogger.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Metadata pulled from a video for time-alignment with sensor data.
 *
 * `creationTimeMillis`: UTC milliseconds when recording started, parsed
 * from the container's `creation_time` (`METADATA_KEY_DATE`). Null when
 * the video has no such tag (rare — most phone cameras and GoPro embed
 * it).
 *
 * `durationMillis`: total length, 0 if unknown.
 */
data class VideoMetadata(
    val creationTimeMillis: Long?,
    val durationMillis: Long,
)

object VideoMetadataReader {
    private const val TAG = "VideoMetadata"

    fun read(context: Context, uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            VideoMetadata(
                creationTimeMillis = dateStr?.let(::parseIso8601Date),
                durationMillis = durationStr?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            Log.w(TAG, "metadata read failed for $uri", e)
            VideoMetadata(null, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * MediaMetadataRetriever returns `creation_time` in one of two
     * shapes depending on container: ISO-8601 ("20251104T143012.000Z")
     * or RFC-3339 ("2025-11-04T14:30:12.000Z"). Try a couple of formats.
     */
    private fun parseIso8601Date(raw: String): Long? {
        val candidates = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in candidates) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(raw)?.time
            } catch (_: Exception) { /* try next */ }
        }
        Log.w(TAG, "could not parse creation_time: $raw")
        return null
    }
}

/** Format a UTC millis timestamp for human display in the local timezone. */
fun formatLocalTime(utcMillis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return fmt.format(Date(utcMillis))
}

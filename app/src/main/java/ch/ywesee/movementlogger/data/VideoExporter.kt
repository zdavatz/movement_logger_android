package ch.ywesee.movementlogger.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Renders a combined video — rider on top, four sensor-data panels stacked
 * below — by handing the source media item to Media3 Transformer with two
 * video effects layered on top:
 *
 *   1. `Presentation.createForWidthAndHeight(outW, outH, LAYOUT_SCALE_TO_FIT)` —
 *      letterboxes the source video into the taller output canvas.
 *   2. `MatrixTransformation` translates the video up by `panelsH / outH`
 *      NDC units so the rider sits flush with the top of the frame.
 *   3. `OverlayEffect(BitmapOverlay)` renders the four panels as a dynamic
 *      bitmap at the bottom of the frame, regenerated per frame so the red
 *      cursor sweeps with the playhead.
 *
 * Audio passthrough is the Transformer default — no audio effects are
 * specified, so the source AAC track is muxed unchanged.
 */
@OptIn(UnstableApi::class)
object VideoExporter {

    private const val TAG = "VideoExporter"

    data class SourceSize(val width: Int, val height: Int, val durationMs: Long)

    /** Probe video dimensions + duration via MediaMetadataRetriever. */
    fun probeSource(context: Context, uri: Uri): SourceSize {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Apply rotation: a 1920×1080 file with rotation=90 is presented as 1080×1920.
            return if (rot == 90 || rot == 270) SourceSize(h, w, dur) else SourceSize(w, h, dur)
        } finally {
            mmr.release()
        }
    }

    /** Output dimensions: keep source width, double the height to fit panels. */
    data class OutputPlan(
        val outWidth: Int,
        val outHeight: Int,
        val panelHeightEach: Int,
        val panelsHeightTotal: Int,
    ) {
        val sourceHeight: Int get() = outHeight - panelsHeightTotal
    }

    fun planOutput(src: SourceSize): OutputPlan {
        val panelEach = src.height / 4               // panels total = source height
        val panelsH = panelEach * 4
        return OutputPlan(
            outWidth = src.width,
            outHeight = src.height + panelsH,
            panelHeightEach = panelEach,
            panelsHeightTotal = panelsH,
        )
    }

    /**
     * Run the combined-video export. Returns the MediaStore URI of the saved
     * file, or throws on failure. The progress callback is invoked with
     * `[0..1]` while the Transformer encodes.
     */
    suspend fun export(
        context: Context,
        sourceUri: Uri,
        sourceSize: SourceSize,
        trimmed: ReplayTrim.TrimmedSession,
        windowStartMs: Long,
        windowEndMs: Long,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.Main) {
        val plan = planOutput(sourceSize)
        val renderer = ExportPanelRenderer(
            trimmed = trimmed,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            outWidth = plan.outWidth,
            panelHeight = plan.panelHeightEach,
        )

        // Output to app cache; we copy into MediaStore after the encode lands.
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "combined_${System.currentTimeMillis()}.mp4")

        val overlay = PanelsBitmapOverlay(
            renderer = renderer,
            windowStartMs = windowStartMs,
            outWidth = plan.outWidth,
            outHeight = plan.outHeight,
            panelsHeight = plan.panelsHeightTotal,
        )

        // Translate the video up by panelsH/outH in NDC so it sits at the
        // top instead of center-letterboxed.
        val shiftNdc = plan.panelsHeightTotal.toFloat() / plan.outHeight.toFloat()
        val moveUp = MatrixTransformation { _ ->
            Matrix().apply { postTranslate(0f, shiftNdc) }
        }

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf(
                        Presentation.createForWidthAndHeight(
                            plan.outWidth,
                            plan.outHeight,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        ),
                        moveUp,
                        OverlayEffect(com.google.common.collect.ImmutableList.of(overlay)),
                    ),
                ),
            )
            .build()

        // Tone-map HDR sources (e.g. iPhone HEIC HEVC HDR clips) down to SDR.
        // Without this, Media3 routes our SDR overlay bitmaps through the
        // Ultra-HDR codepath and trips `checkArgument(bitmap.hasGainmap())`
        // in OverlayShaderProgram — observed on the Ayano_Pump_25.4.2026
        // clip on Pixel 8a.
        val composition = Composition.Builder(
            androidx.media3.transformer.EditedMediaItemSequence(editedMediaItem),
        )
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        runTransformerCoroutine(context, composition, outFile, onProgress)

        // Move the encoded file into MediaStore Movies so it shows up in
        // the Photos app, then return the content URI.
        val savedUri = saveToMoviesCollection(context, outFile, displayName)
        outFile.delete()
        savedUri
    }

    private suspend fun runTransformerCoroutine(
        context: Context,
        composition: Composition,
        outFile: File,
        onProgress: (Float) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { cont ->
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onProgress(1f)
                    if (cont.isActive) cont.resume(exportResult)
                }
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    Log.e(TAG, "Transformer failed", exportException)
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()
        transformer.start(composition, outFile.absolutePath)

        // Poll progress every 200 ms — Transformer reports an int 0..100.
        val progressHolder = androidx.media3.transformer.ProgressHolder()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(tick)
        cont.invokeOnCancellation {
            handler.removeCallbacks(tick)
            transformer.cancel()
        }
    }

    private fun saveToMoviesCollection(context: Context, src: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MovementLogger")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore.insert returned null")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= 29) {
            val finished = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, finished, null, null)
        }
        return uri
    }

    @OptIn(UnstableApi::class)
    private class PanelsBitmapOverlay(
        private val renderer: ExportPanelRenderer,
        private val windowStartMs: Long,
        private val outWidth: Int,
        private val outHeight: Int,
        private val panelsHeight: Int,
    ) : BitmapOverlay() {

        // Anchor the overlay to the bottom of the frame. The size mapping
        // (overlayW/bgW, overlayH/bgH) is already applied by Media3's
        // OverlayMatrixProvider via its aspectRatioMatrix — adding a manual
        // setScale on top would double-shrink the overlay.
        private val settings: OverlaySettings by lazy {
            OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, -1f) // bottom-center of the frame
                .setOverlayFrameAnchor(0f, -1f)    // bottom-center of the overlay bitmap
                .build()
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val absMs = windowStartMs + (presentationTimeUs / 1000L)
            return renderer.renderAt(absMs)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }
}

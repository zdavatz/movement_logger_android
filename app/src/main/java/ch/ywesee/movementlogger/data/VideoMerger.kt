package ch.ywesee.movementlogger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import ch.ywesee.movementlogger.R
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Merges multiple rider clips into one film. Per clip, in chronological
 * order (sorted by container `creation_time` before this is called):
 *
 *   1. A black title card (~2.5 s) with the clip's recording date
 *      (dd.MM.yyyy) above its start time (HH:mm:ss), local timezone.
 *   2. The clip COMPLETE — never trimmed. Hard product rule from the
 *      owner: "never cut a video!". There is no
 *      `MediaItem.ClippingConfiguration` anywhere in this pipeline and
 *      none may ever be added; every source frame ships.
 *   3. A 3-second fade-to-black at the clip's end (full-frame black
 *      overlay whose alpha ramps 0 → 1).
 *
 * When a sensor session (Sens + Gps CSVs, fusion complete) is supplied,
 * each clip is additionally composited with the same four-panel animation
 * as the single-clip Replay export — per clip trimmed to that clip's own
 * time window and aligned via its `creation_time` (which the `# SYNC`
 * anchors put in the same clock domain as the CSV rows).
 *
 * The film closes with a single 5 s Pump Tsüri logo outro — ONCE at the
 * very end (after the last clip's fade-out), not per clip: the foil logo
 * (`R.drawable.pump_logo`, transparent background) centered at ~45 % of
 * the output height on black.
 *
 * Everything is one `EditedMediaItemSequence` (title card, clip, title
 * card, clip, …, outro) so Media3 Transformer concatenates natively. The
 * title cards and the outro are silent images, so the composition forces
 * an audio track — Transformer generates silence under them and passes
 * each clip's own audio through.
 */
@OptIn(UnstableApi::class)
object VideoMerger {

    /** Title-card hold, per product spec (~2.5 s). */
    private const val TITLE_CARD_US = 2_500_000L

    /** Fade-to-black length at each clip's end. */
    private const val FADE_US = 3_000_000L

    /** Pump Tsüri logo outro at the very end of the film. */
    private const val OUTRO_US = 5_000_000L

    private const val CARD_FPS = 30

    /** One source clip with its probed size/duration + capture time. */
    data class Clip(
        val uri: Uri,
        /** UTC ms from the container's `creation_time`; null when absent. */
        val creationTimeMillis: Long?,
        val size: VideoExporter.SourceSize,
    )

    /**
     * The full loaded session (untrimmed parallel arrays, as computed by
     * the Merge screen's fusion pipeline). Each clip trims its own window
     * out of these — mirrors what `ReplayViewModel.exportCombinedVideo`
     * does for one clip.
     */
    class SensorSession(
        val gpsRows: List<GpsRow>,
        val gpsAbsTimesMs: LongArray,
        val speedSmoothedKmh: DoubleArray,
        val sensorAbsTimesMs: LongArray,
        val pitchDeg: DoubleArray,
        val baroHeightM: DoubleArray,
        val fusedHeightM: DoubleArray,
    )

    /**
     * Run the merge export. `clips` must already be sorted chronologically.
     * Returns the MediaStore URI of the saved MP4; the progress callback is
     * invoked with `[0..1]` while the Transformer encodes.
     */
    suspend fun export(
        context: Context,
        clips: List<Clip>,
        session: SensorSession?,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): Uri {
        require(clips.isNotEmpty()) { "no clips to merge" }
        for (c in clips) {
            check(c.size.durationMs > 0L) { "clip has no readable duration: ${c.uri}" }
        }

        // Output canvas from the first (chronologically) clip; every other
        // clip letterboxes into it via Presentation. With sensor data the
        // canvas grows downward for the four panels, exactly like the
        // single-clip export.
        val base = clips.first().size
        val plan = if (session != null) {
            VideoExporter.planOutput(base)
        } else {
            VideoExporter.OutputPlan(
                outWidth = base.width,
                outHeight = base.height,
                panelHeightEach = 0,
                panelsHeightTotal = 0,
            )
        }

        // Title cards + logo outro land as cache PNGs so Media3's image
        // asset loader can pick them up by URI (mime set explicitly, no
        // extension sniffing).
        val cardDir = File(context.cacheDir, "exports/cards").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val (cardFiles, outroFile) = withContext(Dispatchers.IO) {
            val cards = clips.mapIndexed { i, clip ->
                val f = File(cardDir, "card_${stamp}_$i.png")
                val bmp = renderTitleCard(plan.outWidth, plan.outHeight, clip.creationTimeMillis)
                FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                bmp.recycle()
                f
            }
            val outro = File(cardDir, "outro_$stamp.png")
            val outroBmp = renderOutroCard(context, plan.outWidth, plan.outHeight)
            FileOutputStream(outro).use { out -> outroBmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            outroBmp.recycle()
            cards to outro
        }

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "merged_$stamp.mp4")

        try {
            // Build the sequence: [card 0, clip 0, card 1, clip 1, …].
            // Per-item overlays receive SEQUENCE-GLOBAL presentation
            // timestamps (Media3 offsets each item by the elapsed sequence
            // duration), so each clip's overlays carry the clip's global
            // start offset to recover clip-local time.
            val items = withContext(Dispatchers.Default) {
                buildSequenceItems(clips, cardFiles, outroFile, session, plan)
            }

            val composition = Composition.Builder(EditedMediaItemSequence(items))
                // Tone-map HDR sources down to SDR — load-bearing, same
                // rationale as VideoExporter (SDR overlay bitmaps trip the
                // Ultra-HDR codepath on iPhone HDR clips otherwise).
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                // Title cards are silent images; force an audio track so
                // Transformer generates silence under them instead of
                // failing the audio pipeline on a track-count mismatch.
                .experimentalSetForceAudioTrack(true)
                .build()

            withContext(Dispatchers.Main) {
                VideoExporter.runTransformerCoroutine(context, composition, outFile, onProgress)
            }
            return withContext(Dispatchers.IO) {
                VideoExporter.saveToMoviesCollection(context, outFile, displayName)
            }
        } finally {
            outFile.delete()
            cardFiles.forEach { it.delete() }
            outroFile.delete()
        }
    }

    private fun buildSequenceItems(
        clips: List<Clip>,
        cardFiles: List<File>,
        outroFile: File,
        session: SensorSession?,
        plan: VideoExporter.OutputPlan,
    ): List<EditedMediaItem> {
        // Shared full-frame opaque black bitmap for every fade-out.
        val black = Bitmap.createBitmap(plan.outWidth, plan.outHeight, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.BLACK) }
        val shiftNdc = plan.panelsHeightTotal.toFloat() / plan.outHeight.toFloat()

        val items = ArrayList<EditedMediaItem>(clips.size * 2 + 1)
        var cursorUs = 0L
        for ((i, clip) in clips.withIndex()) {
            // (1) Title card — a 2.5 s still image.
            items += EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(cardFiles[i]))
                    .setMimeType(MimeTypes.IMAGE_PNG)
                    .build(),
            )
                .setDurationUs(TITLE_CARD_US)
                .setFrameRate(CARD_FPS)
                .setEffects(
                    Effects(
                        /* audioProcessors = */ emptyList(),
                        /* videoEffects = */ listOf<Effect>(
                            Presentation.createForWidthAndHeight(
                                plan.outWidth, plan.outHeight, Presentation.LAYOUT_SCALE_TO_FIT,
                            ),
                        ),
                    ),
                )
                .build()
            cursorUs += TITLE_CARD_US

            // (2) The clip, complete — NO clipping configuration, ever.
            val clipStartUs = cursorUs
            val clipDurationUs = clip.size.durationMs * 1000L

            val videoEffects = ArrayList<Effect>()
            videoEffects += Presentation.createForWidthAndHeight(
                plan.outWidth, plan.outHeight, Presentation.LAYOUT_SCALE_TO_FIT,
            )
            val overlays = ArrayList<TextureOverlay>()
            if (session != null) {
                // Rider sits flush with the top, panels fill the bottom —
                // same NDC shift as the single-clip export.
                videoEffects += MatrixTransformation { _ ->
                    Matrix().apply { postTranslate(0f, shiftNdc) }
                }
                overlays += buildPanelsOverlay(session, clip, clipStartUs, plan)
            }
            // (3) Fade-out last so it covers panels too.
            overlays += FadeToBlackOverlay(black, clipStartUs, clipDurationUs)
            videoEffects += OverlayEffect(ImmutableList.copyOf(overlays))

            items += EditedMediaItem.Builder(MediaItem.fromUri(clip.uri))
                .setEffects(Effects(/* audioProcessors = */ emptyList(), videoEffects))
                .build()
            cursorUs += clipDurationUs
        }

        // (4) Pump Tsüri logo outro — ONCE at the very end of the film,
        // after the last clip's fade-out.
        items += EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(outroFile))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .build(),
        )
            .setDurationUs(OUTRO_US)
            .setFrameRate(CARD_FPS)
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf<Effect>(
                        Presentation.createForWidthAndHeight(
                            plan.outWidth, plan.outHeight, Presentation.LAYOUT_SCALE_TO_FIT,
                        ),
                    ),
                ),
            )
            .build()
        return items
    }

    /**
     * Panels overlay for one clip: trim the session to the clip's own
     * absolute-time window and render the four-panel stack against it. A
     * clip without `creation_time` (or with no overlapping rows) gets
     * empty panels rather than failing the whole merge.
     */
    private fun buildPanelsOverlay(
        session: SensorSession,
        clip: Clip,
        clipStartUs: Long,
        plan: VideoExporter.OutputPlan,
    ): BitmapOverlay {
        val windowStartMs = clip.creationTimeMillis ?: 0L
        val windowEndMs = windowStartMs + clip.size.durationMs
        val trimmed = ReplayTrim.trimToWindow(
            gpsRows = session.gpsRows,
            gpsAbsTimesMs = session.gpsAbsTimesMs,
            speedSmoothedKmh = session.speedSmoothedKmh,
            sensorAbsTimesMs = session.sensorAbsTimesMs,
            pitchDeg = session.pitchDeg,
            baroHeightM = session.baroHeightM,
            fusedHeightM = session.fusedHeightM,
            startMs = windowStartMs,
            endMs = windowEndMs,
        )
        val renderer = ExportPanelRenderer(
            trimmed = trimmed,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            outWidth = plan.outWidth,
            panelHeight = plan.panelHeightEach,
        )
        return ClipPanelsOverlay(renderer, clipStartUs, windowStartMs)
    }

    /**
     * Outro card: black background at the output resolution with the Pump
     * Tsüri foil logo (transparent PNG) drawn centered at ~45 % of the
     * output height, aspect preserved, alpha respected.
     */
    private fun renderOutroCard(context: Context, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.pump_logo)
        if (logo != null) {
            val targetH = h * 0.45f
            val targetW = targetH * logo.width.toFloat() / logo.height.toFloat()
            val left = (w - targetW) / 2f
            val top = (h - targetH) / 2f
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
            canvas.drawBitmap(logo, null, RectF(left, top, left + targetW, top + targetH), paint)
            logo.recycle()
        }
        return bmp
    }

    /** Black card, date (dd.MM.yyyy) above start time (HH:mm:ss), local tz. */
    private fun renderTitleCard(w: Int, h: Int, creationMs: Long?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val datePaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = h * 0.075f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val timePaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = h * 0.055f
            typeface = Typeface.MONOSPACE
        }
        val cx = w / 2f
        val cy = h / 2f
        if (creationMs != null) {
            // SimpleDateFormat renders in the device's local timezone.
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(creationMs))
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(creationMs))
            canvas.drawText(date, cx, cy - h * 0.02f, datePaint)
            canvas.drawText(time, cx, cy + timePaint.textSize + h * 0.03f, timePaint)
        } else {
            canvas.drawText("Date unknown", cx, cy, datePaint)
        }
        return bmp
    }

    /**
     * The four-panel stack for one clip, bottom-anchored like the
     * single-clip export. `presentationTimeUs` is sequence-global — map it
     * to clip-local time via the clip's global start offset, then to
     * absolute UTC via the clip's creation time.
     */
    private class ClipPanelsOverlay(
        private val renderer: ExportPanelRenderer,
        private val clipStartUs: Long,
        private val windowStartMs: Long,
    ) : BitmapOverlay() {

        private val settings: OverlaySettings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -1f) // bottom-center of the frame
            .setOverlayFrameAnchor(0f, -1f)    // bottom-center of the overlay bitmap
            .build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val localMs = ((presentationTimeUs - clipStartUs) / 1000L).coerceAtLeast(0L)
            return renderer.renderAt(windowStartMs + localMs)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }

    /**
     * Full-frame black overlay whose alpha ramps 0 → 1 over the clip's
     * last 3 s (shorter clips fade over their whole length). The bitmap is
     * a single shared instance, so it uploads to a GL texture once; only
     * the per-frame `alphaScale` changes.
     */
    private class FadeToBlackOverlay(
        private val black: Bitmap,
        private val clipStartUs: Long,
        private val clipDurationUs: Long,
    ) : BitmapOverlay() {

        override fun getBitmap(presentationTimeUs: Long): Bitmap = black

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val localUs = (presentationTimeUs - clipStartUs).coerceIn(0L, clipDurationUs)
            val fadeUs = minOf(FADE_US, clipDurationUs).coerceAtLeast(1L)
            val fadeStartUs = clipDurationUs - fadeUs
            val alpha = ((localUs - fadeStartUs).toFloat() / fadeUs.toFloat()).coerceIn(0f, 1f)
            return OverlaySettings.Builder().setAlphaScale(alpha).build()
        }
    }
}

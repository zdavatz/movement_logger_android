package ch.ywesee.movementlogger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
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
 * Merges multiple rider clips into one film.
 *
 * The film opens with a 3 s intro over the FIRST clip's first frame
 * (aspect-fit into the video region) — "MovementLogger" centered at ~86 %
 * of the output width, each letter colored along the logo gradient
 * (orange → teal → blue → purple) and drawn semi-transparently with a soft
 * shadow so the footage shows through; it falls back to lettering on black
 * when the frame can't be extracted. Then, per clip in chronological order
 * (sorted by container `creation_time` before this is called):
 *
 *   1. A black title card (~2.5 s) with the clip's recording date
 *      (dd.MM.yyyy) above its start time (HH:mm:ss), local timezone.
 *   2. The clip COMPLETE and UNFADED — never trimmed, never dimmed while
 *      footage still plays. Hard product rules from the owner: "never
 *      cut a video!" and "play every movie till the end and then add
 *      phase out over 3 seconds". There is no
 *      `MediaItem.ClippingConfiguration` anywhere in this pipeline and
 *      none may ever be added; every source frame ships at full
 *      brightness.
 *   3. A 3-second FREEZE of the clip's last frame (extracted via
 *      MediaMetadataRetriever, inserted as an image item) over which the
 *      full-frame black overlay's alpha ramps 0 → 1 — the fade lives
 *      entirely on the frozen still, mirroring the desktop pipeline.
 *
 * When a sensor session (Sens + Gps CSVs, fusion complete) is supplied,
 * each clip is additionally composited with the same four-panel animation
 * as the single-clip Replay export — per clip trimmed to that clip's own
 * time window and aligned via its `creation_time` (which the `# SYNC`
 * anchors put in the same clock domain as the CSV rows). The freeze
 * segment keeps the panels (cursor pinned at the clip's end) under its
 * fade.
 *
 * The film closes with a single 3 s animated "pumping" outro — ONCE at the
 * very end (after the last freeze's fade-out), not per clip: the foil logo
 * (`R.drawable.pump_logo`, transparent) rocks about its wings + a synced
 * vertical bob + squash (cadence/amplitudes mapped from Ayano's pumping
 * footage) over a sky→sea gradient, fading in from the black the last
 * freeze leaves and back out to black.
 *
 * Everything is one `EditedMediaItemSequence` (intro, card, clip, freeze,
 * card, clip, freeze, …, outro) so Media3 Transformer concatenates
 * natively. The intro/title/freeze/outro stills are silent images, so the
 * composition forces an audio track — Transformer generates silence under
 * them and passes each clip's own audio through.
 */
@OptIn(UnstableApi::class)
object VideoMerger {

    /** "MovementLogger" gradient intro card at the very start of the film. */
    private const val INTRO_US = 3_000_000L

    /** Title-card hold, per product spec (~2.5 s). */
    private const val TITLE_CARD_US = 2_500_000L

    /**
     * Last-frame freeze after each clip; the 3 s fade-to-black ramps
     * across this frozen still — the clip itself plays complete, unfaded.
     */
    private const val FREEZE_US = 3_000_000L

    /** Animated pumping-foil outro at the very end of the film. */
    private const val OUTRO_US = 3_000_000L

    private const val CARD_FPS = 30

    private const val INTRO_TEXT = "MovementLogger"

    /** Logo gradient stops for the intro letters: orange → teal → blue → purple. */
    private val INTRO_GRADIENT = arrayOf(
        intArrayOf(247, 154, 51),
        intArrayOf(36, 195, 188),
        intArrayOf(62, 141, 243),
        intArrayOf(125, 77, 240),
    )

    // ---- Pumping-foil outro (mapped from Ayano's IMG_5266.MOV pumping
    // footage): the foil icon rocks about its wings + a synced vertical bob +
    // squash over a sky→sea gradient, fading in from black and back out.
    private const val PUMP_FPS = 30
    private const val PUMP_PERIOD_S = 1.05        // ~3 pumps over 3 s
    private const val PUMP_PITCH_DEG = 11.0       // rock amplitude (°)
    private const val PUMP_HEAVE_FRAC = 0.021     // vertical bob (of height)
    private const val PUMP_SQUASH = 0.035         // compress/extend
    private const val PUMP_LOGO_FRAC = 0.55f      // foil size (of height)
    private const val PUMP_PIVOT_FRAC = 0.78f     // wings pivot (down the foil)
    private const val PUMP_FADE_IN_US = 350_000L
    private const val PUMP_FADE_OUT_US = 750_000L
    /** Sky → horizon → sea gradient stops for the outro background. */
    private val PUMP_SKY = intArrayOf(189, 227, 245)
    private val PUMP_HORIZON = intArrayOf(107, 184, 212)
    private val PUMP_SEA = intArrayOf(28, 87, 128)

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

        // Intro, title cards, per-clip last-frame freezes and the logo
        // outro land as cache PNGs so Media3's image asset loader can pick
        // them up by URI (mime set explicitly, no extension sniffing).
        val cardDir = File(context.cacheDir, "exports/cards").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        // Video region excludes the panel area (== full canvas for a plain
        // merge). The intro title floats over the first frame within it.
        val videoRegionH = plan.outHeight - plan.panelsHeightTotal
        // The foil logo drives the animated outro; held (not recycled) until
        // the export finishes since the outro overlay reads it per frame.
        val pumpLogo: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.pump_logo)
        val stills = withContext(Dispatchers.IO) {
            fun writePng(name: String, bmp: Bitmap): File {
                val f = File(cardDir, name)
                FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                bmp.recycle()
                return f
            }
            val firstFrame = extractFirstFrame(context, clips.first())
            val s = Stills(
                intro = writePng(
                    "intro_$stamp.png",
                    renderIntroCard(plan.outWidth, plan.outHeight, videoRegionH, firstFrame),
                ),
                cards = clips.mapIndexed { i, clip ->
                    writePng(
                        "card_${stamp}_$i.png",
                        renderTitleCard(plan.outWidth, plan.outHeight, clip.creationTimeMillis),
                    )
                },
                freezes = clips.mapIndexed { i, clip ->
                    writePng("freeze_${stamp}_$i.png", extractLastFrame(context, clip))
                },
                // The outro base is the sky→sea gradient; the pumping foil +
                // fade ride on top as overlays (see buildSequenceItems).
                outro = writePng("outro_$stamp.png", renderSkySeaGradient(plan.outWidth, plan.outHeight)),
            )
            firstFrame?.recycle()
            s
        }

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "merged_$stamp.mp4")

        try {
            // Build the sequence: [intro, card 0, clip 0, freeze 0, …].
            // Per-item overlays receive SEQUENCE-GLOBAL presentation
            // timestamps (Media3 offsets each item by the elapsed sequence
            // duration), so each clip's overlays carry the clip's global
            // start offset to recover clip-local time.
            val items = withContext(Dispatchers.Default) {
                buildSequenceItems(clips, stills, session, plan, pumpLogo)
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
            stills.allFiles().forEach { it.delete() }
            pumpLogo?.recycle()
        }
    }

    /** Cache-PNG stills that frame the clips in the sequence. */
    private class Stills(
        val intro: File,
        val cards: List<File>,
        val freezes: List<File>,
        val outro: File,
    ) {
        fun allFiles(): List<File> = buildList {
            add(intro)
            addAll(cards)
            addAll(freezes)
            add(outro)
        }
    }

    private fun buildSequenceItems(
        clips: List<Clip>,
        stills: Stills,
        session: SensorSession?,
        plan: VideoExporter.OutputPlan,
        pumpLogo: Bitmap?,
    ): List<EditedMediaItem> {
        // Shared full-frame opaque black bitmap for every fade-out.
        val black = Bitmap.createBitmap(plan.outWidth, plan.outHeight, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.BLACK) }
        val shiftNdc = plan.panelsHeightTotal.toFloat() / plan.outHeight.toFloat()
        val presentation = Presentation.createForWidthAndHeight(
            plan.outWidth, plan.outHeight, Presentation.LAYOUT_SCALE_TO_FIT,
        )

        /** A silent still with just the scale-to-fit Presentation. */
        fun stillItem(file: File, durationUs: Long): EditedMediaItem =
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMimeType(MimeTypes.IMAGE_PNG)
                    .build(),
            )
                .setDurationUs(durationUs)
                .setFrameRate(CARD_FPS)
                .setEffects(
                    Effects(/* audioProcessors = */ emptyList(), listOf<Effect>(presentation)),
                )
                .build()

        val items = ArrayList<EditedMediaItem>(clips.size * 3 + 2)
        var cursorUs = 0L

        // (0) "MovementLogger" gradient intro — 3 s, once, before the first
        // date card.
        items += stillItem(stills.intro, INTRO_US)
        cursorUs += INTRO_US

        for ((i, clip) in clips.withIndex()) {
            // (1) Title card — a 2.5 s still image.
            items += stillItem(stills.cards[i], TITLE_CARD_US)
            cursorUs += TITLE_CARD_US

            // (2) The clip, complete and UNFADED — NO clipping
            // configuration, ever, and no dimming while footage plays.
            val clipStartUs = cursorUs
            val clipDurationUs = clip.size.durationMs * 1000L
            val panels = if (session != null) buildClipPanels(session, clip, plan) else null

            val clipEffects = ArrayList<Effect>()
            clipEffects += presentation
            if (panels != null) {
                // Rider sits flush with the top, panels fill the bottom —
                // same NDC shift as the single-clip export.
                clipEffects += MatrixTransformation { _ ->
                    Matrix().apply { postTranslate(0f, shiftNdc) }
                }
                clipEffects += OverlayEffect(
                    ImmutableList.of<TextureOverlay>(
                        ClipPanelsOverlay(panels.renderer, clipStartUs, panels.windowStartMs),
                    ),
                )
            }
            items += EditedMediaItem.Builder(MediaItem.fromUri(clip.uri))
                .setEffects(Effects(/* audioProcessors = */ emptyList(), clipEffects))
                .build()
            cursorUs += clipDurationUs

            // (3) Freeze of the clip's last frame, fading to black over
            // 3 s. The panels ride along (cursor pinned at clip end —
            // ClipPanelsOverlay keeps the clip's own start offset, so the
            // freeze's global timestamps land past the window and the
            // renderer clamps to the last row); the fade overlays them.
            val freezeStartUs = cursorUs
            val freezeEffects = ArrayList<Effect>()
            freezeEffects += presentation
            val freezeOverlays = ArrayList<TextureOverlay>()
            if (panels != null) {
                freezeEffects += MatrixTransformation { _ ->
                    Matrix().apply { postTranslate(0f, shiftNdc) }
                }
                freezeOverlays += ClipPanelsOverlay(panels.renderer, clipStartUs, panels.windowStartMs)
            }
            freezeOverlays += FadeToBlackOverlay(black, freezeStartUs, FREEZE_US)
            freezeEffects += OverlayEffect(ImmutableList.copyOf(freezeOverlays))

            items += EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(stills.freezes[i]))
                    .setMimeType(MimeTypes.IMAGE_PNG)
                    .build(),
            )
                .setDurationUs(FREEZE_US)
                .setFrameRate(CARD_FPS)
                .setEffects(Effects(/* audioProcessors = */ emptyList(), freezeEffects))
                .build()
            cursorUs += FREEZE_US
        }

        // (4) Pumping-foil outro — ONCE at the very end, after the last
        // freeze's fade-out. The sky→sea gradient still is the base; the foil
        // rocks about its wings on top (PumpFoilOverlay), and a two-sided
        // black fade brings it in from the freeze's black and back out.
        val outroStartUs = cursorUs
        val outroOverlays = ArrayList<TextureOverlay>()
        if (pumpLogo != null) {
            outroOverlays += PumpFoilOverlay(
                plan.outWidth, plan.outHeight, pumpLogo, outroStartUs, OUTRO_US,
            )
        }
        outroOverlays += FadeInOutBlackOverlay(
            black, outroStartUs, OUTRO_US, PUMP_FADE_IN_US, PUMP_FADE_OUT_US,
        )
        items += EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(stills.outro))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .build(),
        )
            .setDurationUs(OUTRO_US)
            .setFrameRate(CARD_FPS)
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    listOf<Effect>(presentation, OverlayEffect(ImmutableList.copyOf(outroOverlays))),
                ),
            )
            .build()
        return items
    }

    /** Per-clip panels renderer + the clip's absolute-time window start. */
    private class ClipPanels(val renderer: ExportPanelRenderer, val windowStartMs: Long)

    /**
     * Panels renderer for one clip: trim the session to the clip's own
     * absolute-time window and render the four-panel stack against it. A
     * clip without `creation_time` (or with no overlapping rows) gets
     * empty panels rather than failing the whole merge. The renderer is
     * shared by the clip item's overlay and the freeze item's overlay.
     */
    private fun buildClipPanels(
        session: SensorSession,
        clip: Clip,
        plan: VideoExporter.OutputPlan,
    ): ClipPanels {
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
        return ClipPanels(renderer, windowStartMs)
    }

    /**
     * The FIRST clip's first frame for the intro background, in display
     * orientation. Null on failure — the intro then falls back to lettering
     * on solid black.
     */
    private fun extractFirstFrame(context: Context, clip: Clip): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, clip.uri)
            mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0L)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * The clip's last frame for the freeze segment, in display
     * orientation (getFrameAtTime applies container rotation). Falls back
     * to a representative frame, then plain black — the fade still works
     * either way.
     */
    private fun extractLastFrame(context: Context, clip: Clip): Bitmap {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, clip.uri)
            val frame = mmr.getFrameAtTime(
                clip.size.durationMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: mmr.getFrameAtTime(-1)
            if (frame != null) return frame
        } catch (_: Exception) {
            // fall through to the black fallback
        } finally {
            runCatching { mmr.release() }
        }
        return Bitmap.createBitmap(
            clip.size.width.coerceAtLeast(2),
            clip.size.height.coerceAtLeast(2),
            Bitmap.Config.ARGB_8888,
        ).apply { eraseColor(Color.BLACK) }
    }

    /**
     * Intro card: the first clip's first frame (aspect-fit into the video
     * region, black letterbox) with "MovementLogger" centered over it, sized
     * so the word spans ~86 % of the width, each letter colored along the
     * logo gradient. Over footage the lettering is semi-transparent (0.85 α)
     * with a soft shadow so the frame shows through; on a null background it
     * renders fully opaque on solid black (the legacy intro).
     */
    private fun renderIntroCard(w: Int, h: Int, videoRegionH: Int, background: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val regionH = if (videoRegionH > 0) videoRegionH else h
        if (background != null && background.width > 0 && background.height > 0) {
            val fit = minOf(
                w.toFloat() / background.width, regionH.toFloat() / background.height,
            )
            val fw = background.width * fit
            val fh = background.height * fit
            val left = (w - fw) / 2f
            val top = (regionH - fh) / 2f
            canvas.drawBitmap(
                background, null, RectF(left, top, left + fw, top + fh),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true },
            )
        }
        val paint = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 100f
        }
        // Scale the text so the full word spans ~86 % of the width.
        val measured = paint.measureText(INTRO_TEXT)
        if (measured > 0f) paint.textSize = 100f * (w * 0.86f) / measured
        val alpha = if (background != null) 217 else 255   // 0.85 over footage
        if (background != null) {
            paint.setShadowLayer(paint.textSize * 0.08f, 0f, 0f, Color.argb(180, 0, 0, 0))
        }
        val fm = paint.fontMetrics
        // Center the title within the VIDEO region (over the frame), not the
        // full canvas — matters when panels extend the canvas downward.
        val baseline = regionH / 2f - (fm.ascent + fm.descent) / 2f
        var x = (w - paint.measureText(INTRO_TEXT)) / 2f
        for ((i, ch) in INTRO_TEXT.withIndex()) {
            val c0 = introLetterColor(i, INTRO_TEXT.length)
            paint.color = Color.argb(alpha, Color.red(c0), Color.green(c0), Color.blue(c0))
            val s = ch.toString()
            canvas.drawText(s, x, baseline, paint)
            x += paint.measureText(s)
        }
        return bmp
    }

    /** Gradient color for intro letter `i` of `n` (piecewise-linear RGB). */
    private fun introLetterColor(i: Int, n: Int): Int {
        val t = if (n <= 1) 0f else i.toFloat() / (n - 1).toFloat()
        val segments = INTRO_GRADIENT.size - 1
        val x = t * segments
        val seg = x.toInt().coerceIn(0, segments - 1)
        val f = x - seg
        val a = INTRO_GRADIENT[seg]
        val b = INTRO_GRADIENT[seg + 1]
        fun lerp(u: Int, v: Int): Int = (u + (v - u) * f).toInt().coerceIn(0, 255)
        return Color.rgb(lerp(a[0], b[0]), lerp(a[1], b[1]), lerp(a[2], b[2]))
    }

    /** Sky → horizon → sea vertical gradient — the pumping outro background. */
    private fun renderSkySeaGradient(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val grad = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(PUMP_SKY[0], PUMP_SKY[1], PUMP_SKY[2]),
                Color.rgb(PUMP_HORIZON[0], PUMP_HORIZON[1], PUMP_HORIZON[2]),
                Color.rgb(PUMP_SEA[0], PUMP_SEA[1], PUMP_SEA[2]),
            ),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = grad })
        return bmp
    }

    /**
     * One frame of the pumping foil (transparent background, full canvas):
     * the foil rocks ±PUMP_PITCH_DEG about its wings + a synced vertical bob
     * + squash. Frame `i` of `n`. Composited over the gradient base by
     * `PumpFoilOverlay`; the fade is a separate overlay.
     */
    private fun renderPumpFoilFrame(w: Int, h: Int, logo: Bitmap, i: Int, n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)   // transparent
        val canvas = Canvas(bmp)
        val secs = i.toDouble() / PUMP_FPS
        val phase = 2.0 * Math.PI / PUMP_PERIOD_S * secs
        val logoH = h * PUMP_LOGO_FRAC
        val logoW = logoH * logo.width.toFloat() / logo.height.toFloat()
        val left = (w - logoW) / 2f
        val top = (h - logoH) / 2f
        val pivotX = left + logoW / 2f
        val pivotY = top + PUMP_PIVOT_FRAC * logoH   // wings, down from the top
        val thetaDeg = (PUMP_PITCH_DEG * Math.sin(phase)).toFloat()
        val dyPx = (-PUMP_HEAVE_FRAC * h * Math.sin(phase)).toFloat()   // rise on nose-up
        val sy = (1.0 - PUMP_SQUASH * Math.cos(phase)).toFloat()        // squash at compress
        canvas.save()
        canvas.translate(0f, dyPx)                 // heave
        canvas.rotate(thetaDeg, pivotX, pivotY)    // rock about the wings
        canvas.scale(1f, sy, pivotX, pivotY)       // squash about the wings
        canvas.drawBitmap(
            logo, null, RectF(left, top, left + logoW, top + logoH),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true },
        )
        canvas.restore()
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
     * Full-frame black overlay whose alpha ramps 0 → 1 across the freeze
     * segment that follows each clip. Applied ONLY to that frozen still —
     * never to the clip itself, which must play complete and unfaded
     * ("play every movie till the end and then add phase out over
     * 3 seconds"). The bitmap is a single shared instance, so it uploads
     * to a GL texture once; only the per-frame `alphaScale` changes.
     */
    private class FadeToBlackOverlay(
        private val black: Bitmap,
        private val segmentStartUs: Long,
        private val segmentDurationUs: Long,
    ) : BitmapOverlay() {

        override fun getBitmap(presentationTimeUs: Long): Bitmap = black

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val localUs = (presentationTimeUs - segmentStartUs).coerceIn(0L, segmentDurationUs)
            val alpha = (localUs.toFloat() / segmentDurationUs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
            return OverlaySettings.Builder().setAlphaScale(alpha).build()
        }
    }

    /**
     * Full-frame overlay that draws the pumping foil (transformed per frame)
     * over the outro's gradient base. Mirrors iOS `pumpFrameImage`: the foil
     * rocks about its wings + bobs + squashes on a sine of the segment-local
     * time. A fresh bitmap per frame (like `ClipPanelsOverlay`) so Media3
     * re-uploads the changed texture each output frame.
     */
    private class PumpFoilOverlay(
        private val w: Int,
        private val h: Int,
        private val logo: Bitmap,
        private val segmentStartUs: Long,
        private val segmentDurationUs: Long,
    ) : BitmapOverlay() {

        private val settings: OverlaySettings = OverlaySettings.Builder().build()
        private val frames: Int =
            (segmentDurationUs * PUMP_FPS / 1_000_000L).toInt().coerceAtLeast(1)

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val localUs = (presentationTimeUs - segmentStartUs).coerceIn(0L, segmentDurationUs)
            val i = (localUs * PUMP_FPS / 1_000_000L).toInt().coerceIn(0, frames - 1)
            return renderPumpFoilFrame(w, h, logo, i, frames)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }

    /**
     * Full-frame black overlay whose alpha fades IN from black over the first
     * `fadeInUs` and OUT to black over the last `fadeOutUs` of a segment.
     * Used to bring the pumping outro in from the freeze's black and back out
     * to end the film. Reuses the single shared `black` bitmap.
     */
    private class FadeInOutBlackOverlay(
        private val black: Bitmap,
        private val segmentStartUs: Long,
        private val segmentDurationUs: Long,
        private val fadeInUs: Long,
        private val fadeOutUs: Long,
    ) : BitmapOverlay() {

        override fun getBitmap(presentationTimeUs: Long): Bitmap = black

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val localUs = (presentationTimeUs - segmentStartUs).coerceIn(0L, segmentDurationUs)
            val cover = when {
                localUs < fadeInUs ->
                    1f - localUs.toFloat() / fadeInUs.coerceAtLeast(1L).toFloat()
                localUs > segmentDurationUs - fadeOutUs ->
                    (localUs - (segmentDurationUs - fadeOutUs)).toFloat() /
                        fadeOutUs.coerceAtLeast(1L).toFloat()
                else -> 0f
            }.coerceIn(0f, 1f)
            return OverlaySettings.Builder().setAlphaScale(cover).build()
        }
    }
}

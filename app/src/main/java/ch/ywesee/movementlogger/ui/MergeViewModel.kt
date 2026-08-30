package ch.ywesee.movementlogger.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.ywesee.movementlogger.data.Baro
import ch.ywesee.movementlogger.data.CsvParsers
import ch.ywesee.movementlogger.data.Fusion
import ch.ywesee.movementlogger.data.FusionHeight
import ch.ywesee.movementlogger.data.GpsMath
import ch.ywesee.movementlogger.data.GpsRow
import ch.ywesee.movementlogger.data.GpsTime
import ch.ywesee.movementlogger.data.SensorRow
import ch.ywesee.movementlogger.data.SyncAnchor
import ch.ywesee.movementlogger.data.SyncTimeAlign
import ch.ywesee.movementlogger.data.VideoExporter
import ch.ywesee.movementlogger.data.VideoMerger
import ch.ywesee.movementlogger.data.VideoMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One picked clip after probing, shown in the ordered list. */
data class MergeClipInfo(
    val uri: Uri,
    val displayName: String,
    /** UTC ms from the container's `creation_time`; null when absent. */
    val creationTimeMillis: Long?,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    /** Source file size in bytes (from OpenableColumns.SIZE) — drives the
     *  merged-output size estimate / 2 GB WhatsApp guard. */
    val sizeBytes: Long = 0L,
)

data class MergeUiState(
    /** Picked clips, already sorted chronologically by capture time. */
    val clips: List<MergeClipInfo> = emptyList(),
    val sensorFile: File? = null,
    val gpsFile: File? = null,
    val sensorRows: List<SensorRow> = emptyList(),
    val gpsRows: List<GpsRow> = emptyList(),
    val sensorSyncAnchors: List<SyncAnchor> = emptyList(),
    val gpsSyncAnchors: List<SyncAnchor> = emptyList(),
    val speedSmoothedKmh: DoubleArray = DoubleArray(0),
    val gpsAbsTimesMs: LongArray = LongArray(0),
    val pitchDeg: DoubleArray = DoubleArray(0),
    val baroHeightM: DoubleArray = DoubleArray(0),
    val fusedHeightM: DoubleArray = DoubleArray(0),
    val sensorAbsTimesMs: LongArray = LongArray(0),
    val loading: Boolean = false,
    val computing: Boolean = false,
    val error: String? = null,
    val exportState: ExportState = ExportState.Idle,
    /** Optional background-music track (a LOCAL audio file the user supplied
     *  — imported from the picker or already in the app's Music folder).
     *  Nothing is ever downloaded in-app. */
    val musicUri: Uri? = null,
    val musicName: String? = null,
    /** Background-music level, 0…1 (below the footage audio by default). */
    val musicVolume: Float = 0.35f,
    /** Drop the clips' original sound so the music carries the film alone. */
    val muteClipAudio: Boolean = false,
    /** Estimated size of the merged film (bytes), refreshed whenever the clip
     *  set or panel selection changes — so the user is warned BEFORE the
     *  (many-minute) encode. null = unknown. */
    val estimatedExportBytes: Long? = null,
    /** True when the estimate is at/over WhatsApp's 2 GB share ceiling. */
    val exceedsShareLimit: Boolean = false,
) {
    /** Sensor panels composite only when both CSVs are loaded + fusion done. */
    val sensorsReady: Boolean
        get() = gpsRows.isNotEmpty() && pitchDeg.isNotEmpty()
}

/**
 * Merge screen — pick multiple videos, sort them by capture time, and
 * render one film: per clip a 2.5 s black title card (date + start time),
 * the COMPLETE clip (hard product rule: never cut a video), and a 3 s
 * fade-to-black. Optionally composites the same sensor panels as the
 * single-clip Replay export when Sens + Gps CSVs are loaded.
 */
class MergeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state.asStateFlow()

    /** `Sens*` / `Gps*` files saved by the Sync tab — same source as Replay. */
    fun listLocalRecordings(): List<File> {
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * Probe every picked video and sort the batch chronologically by its
     * container `creation_time`. Picking order is irrelevant; clips with
     * no readable creation time sort last (in picking order).
     */
    fun pickVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val ctx = getApplication<Application>().applicationContext
            try {
                val infos = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val meta = VideoMetadataReader.read(ctx, uri)
                        val size = VideoExporter.probeSource(ctx, uri)
                        MergeClipInfo(
                            uri = uri,
                            displayName = queryDisplayName(uri),
                            creationTimeMillis = meta.creationTimeMillis,
                            durationMillis = if (size.durationMs > 0) size.durationMs else meta.durationMillis,
                            width = size.width,
                            height = size.height,
                            sizeBytes = querySize(uri),
                        )
                    }.sortedBy { it.creationTimeMillis ?: Long.MAX_VALUE }
                }
                _state.update { it.copy(clips = infos, loading = false) }
                refreshEstimate()
                // hhmmss.ss GPS rows without `# SYNC` anchors were anchored
                // to "today" if they loaded before the videos — re-anchor to
                // the session date now that clip creation times are known.
                reanchorGpsTimes()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Videos: ${e.message}") }
            }
        }
    }

    private fun reanchorGpsTimes() {
        val s = _state.value
        if (s.gpsRows.isEmpty() || s.gpsSyncAnchors.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val times = gpsAbsTimes(s.gpsRows, s.gpsSyncAnchors)
            val sensorTimes = if (s.sensorRows.isNotEmpty()) {
                sensorAbsTimes(s.sensorRows, s.sensorSyncAnchors, s.gpsRows, times)
            } else null
            _state.update {
                it.copy(
                    gpsAbsTimesMs = times,
                    sensorAbsTimesMs = sensorTimes ?: it.sensorAbsTimesMs,
                )
            }
        }
    }

    fun clearClips() {
        _state.update { it.copy(clips = emptyList()) }
        refreshEstimate()
    }

    fun pickSensorCsv(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (rows, anchors) = withContext(Dispatchers.IO) {
                    CsvParsers.parseSensorFile(file) to CsvParsers.parseSyncAnchorsFile(file)
                }
                _state.update {
                    it.copy(
                        sensorFile = file, sensorRows = rows,
                        sensorSyncAnchors = anchors, loading = false,
                    )
                }
                maybeComputeFusion()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Sensor: ${e.message}") }
            }
        }
    }

    fun pickGpsCsv(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val rows = CsvParsers.parseGpsFile(file)
                    val gpsAnchors = CsvParsers.parseSyncAnchorsFile(file)
                    val raw = GpsMath.positionDerivedSpeedKmh(rows)
                    val cleaned = GpsMath.rejectAccOutliers(rows, raw)
                    val smooth = GpsMath.smoothSpeedKmh(cleaned)
                    Triple(rows, gpsAnchors, smooth)
                }
                val (rows, gpsAnchors, smoothed) = loaded
                val absTimes = gpsAbsTimes(rows, gpsAnchors)
                _state.update {
                    it.copy(
                        gpsFile = file,
                        gpsRows = rows,
                        gpsSyncAnchors = gpsAnchors,
                        speedSmoothedKmh = smoothed,
                        gpsAbsTimesMs = absTimes,
                        loading = false,
                    )
                }
                maybeComputeFusion()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "GPS: ${e.message}") }
            }
        }
    }

    fun clearSensorData() {
        _state.update {
            it.copy(
                sensorFile = null, gpsFile = null,
                sensorRows = emptyList(), gpsRows = emptyList(),
                sensorSyncAnchors = emptyList(), gpsSyncAnchors = emptyList(),
                speedSmoothedKmh = DoubleArray(0), gpsAbsTimesMs = LongArray(0),
                pitchDeg = DoubleArray(0), baroHeightM = DoubleArray(0),
                fusedHeightM = DoubleArray(0), sensorAbsTimesMs = LongArray(0),
            )
        }
        refreshEstimate()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearExportState() {
        _state.update { it.copy(exportState = ExportState.Idle) }
    }

    // ── background music (optional) ──────────────────────────────────────

    /**
     * Canonical on-device home for background-music tracks: the app's
     * external files `Music/` folder (created on demand). Drop audio here
     * (`adb push … files/Music/`) and it appears in the picker; the same
     * folder receives files imported through the system picker. Mirrors
     * iOS's `Documents/Music/`.
     */
    fun musicDir(): File {
        val base = getApplication<Application>().getExternalFilesDir("Music")
            ?: File(getApplication<Application>().filesDir, "Music")
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** Audio files already in the Music folder (newest-first). */
    fun listLocalAudio(): List<File> {
        val exts = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "3gp")
        return musicDir().listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in exts }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Copy a picked audio content-Uri into the Music folder and select it. */
    fun pickMusic(uri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>().applicationContext
                val name = queryDisplayName(uri)
                val dest = withContext(Dispatchers.IO) {
                    val out = File(musicDir(), sanitizeFileName(name))
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("cannot read audio file")
                    out
                }
                _state.update { it.copy(musicUri = Uri.fromFile(dest), musicName = dest.name) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Music: ${e.message}") }
            }
        }
    }

    /** Select an audio file already present in the Music folder. */
    fun selectMusicFile(file: File) {
        _state.update { it.copy(musicUri = Uri.fromFile(file), musicName = file.name) }
    }

    fun clearMusic() {
        _state.update { it.copy(musicUri = null, musicName = null) }
    }

    fun setMusicVolume(v: Float) {
        _state.update { it.copy(musicVolume = v.coerceIn(0f, 1f)) }
    }

    fun setMuteClipAudio(mute: Boolean) {
        _state.update { it.copy(muteClipAudio = mute) }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\]"), "_").ifBlank { "music_${System.currentTimeMillis()}.m4a" }

    /** Same fusion chain as the Replay tab — see `ReplayViewModel.maybeComputeFusion`. */
    private fun maybeComputeFusion() {
        val s = _state.value
        if (s.sensorRows.isEmpty() || s.gpsRows.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(computing = true, error = null) }
            try {
                val result = withContext(Dispatchers.Default) {
                    val dt = Fusion.detectDtSeconds(s.sensorRows)
                    val sampleHz = 1.0 / dt
                    val quats = Fusion.computeQuaternions(s.sensorRows, beta = 0.1)
                    val pitch = Fusion.noseAngleSeriesDeg(quats, sampleHz.toInt().coerceAtLeast(1))
                    val baseTicks = s.sensorRows.first().ticks
                    val baroH = Baro.heightAboveWaterM(
                        s.sensorRows, s.gpsRows, s.speedSmoothedKmh, baseTicks,
                    )
                    val fusedH = FusionHeight.fusedHeightM(s.sensorRows, quats, baroH, sampleHz)
                    val sensorTimes = sensorAbsTimes(
                        s.sensorRows, s.sensorSyncAnchors, s.gpsRows, s.gpsAbsTimesMs,
                    )
                    FusionResults(pitch, baroH, fusedH, sensorTimes)
                }
                _state.update {
                    it.copy(
                        pitchDeg = result.pitch,
                        baroHeightM = result.baroH,
                        fusedHeightM = result.fusedH,
                        sensorAbsTimesMs = result.sensorTimes,
                        computing = false,
                    )
                }
                refreshEstimate()   // panels now composite → taller frame
            } catch (e: Exception) {
                _state.update { it.copy(computing = false, error = "fusion: ${e.message}") }
            }
        }
    }

    private data class FusionResults(
        val pitch: DoubleArray,
        val baroH: DoubleArray,
        val fusedH: DoubleArray,
        val sensorTimes: LongArray,
    )

    /**
     * Merge everything into one MP4. Clips are already chronological; each
     * plays complete with title card + fade-out. Sensor panels ride along
     * when the session is loaded (clips without creation_time get empty
     * panels rather than aborting the merge).
     */
    fun mergeVideos() {
        val s = _state.value
        if (s.clips.isEmpty()) {
            _state.update { it.copy(exportState = ExportState.Failed("Pick videos first")) }
            return
        }
        val noDuration = s.clips.firstOrNull { it.durationMillis <= 0L }
        if (noDuration != null) {
            _state.update {
                it.copy(exportState = ExportState.Failed("No readable duration: ${noDuration.displayName}"))
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(exportState = ExportState.Running(0f)) }
            try {
                val ctx = getApplication<Application>().applicationContext
                val clips = s.clips.map {
                    VideoMerger.Clip(
                        uri = it.uri,
                        creationTimeMillis = it.creationTimeMillis,
                        size = VideoExporter.SourceSize(it.width, it.height, it.durationMillis),
                    )
                }
                val session = if (s.sensorsReady) {
                    VideoMerger.SensorSession(
                        gpsRows = s.gpsRows,
                        gpsAbsTimesMs = s.gpsAbsTimesMs,
                        speedSmoothedKmh = s.speedSmoothedKmh,
                        sensorAbsTimesMs = s.sensorAbsTimesMs,
                        pitchDeg = s.pitchDeg,
                        baroHeightM = s.baroHeightM,
                        fusedHeightM = s.fusedHeightM,
                    )
                } else null
                val displayName = "MovementLogger_Merged_${System.currentTimeMillis()}.mp4"
                val savedUri = VideoMerger.export(
                    context = ctx,
                    clips = clips,
                    session = session,
                    displayName = displayName,
                    backgroundMusic = s.musicUri,
                    musicVolume = s.musicVolume,
                    muteClipAudio = s.muteClipAudio,
                ) { p ->
                    _state.update { it.copy(exportState = ExportState.Running(p)) }
                }
                _state.update { it.copy(exportState = ExportState.Done(savedUri)) }
            } catch (e: Exception) {
                _state.update { it.copy(exportState = ExportState.Failed(e.message ?: "merge failed")) }
            }
        }
    }

    // ── time alignment — same anchor-preferred mapping as Replay ─────────

    /** Y/M/D for interpreting `hhmmss.ss` UTC strings on the fallback path. */
    private fun sessionDate(): Triple<Int, Int, Int> {
        val creation = _state.value.clips.firstOrNull { it.creationTimeMillis != null }
            ?.creationTimeMillis
        return if (creation != null) GpsTime.utcYmdFromMillis(creation) else GpsTime.todayUtc()
    }

    private fun gpsAbsTimes(rows: List<GpsRow>, anchors: List<SyncAnchor>): LongArray {
        if (anchors.isNotEmpty()) {
            return SyncTimeAlign.absTimesFromSyncAnchors(
                DoubleArray(rows.size) { rows[it].ticks }, anchors,
            )
        }
        val (y, mo, d) = sessionDate()
        return LongArray(rows.size) { i -> GpsTime.toUtcMillis(rows[i].utc, y, mo, d) ?: -1L }
    }

    private fun sensorAbsTimes(
        sensorRows: List<SensorRow>,
        sensorAnchors: List<SyncAnchor>,
        gpsRows: List<GpsRow>,
        gpsAbsTimesMs: LongArray,
    ): LongArray {
        if (sensorAnchors.isNotEmpty()) {
            return SyncTimeAlign.absTimesFromSyncAnchors(
                DoubleArray(sensorRows.size) { sensorRows[it].ticks }, sensorAnchors,
            )
        }
        val anchor = gpsAbsTimesMs.firstOrNull { it >= 0L } ?: 0L
        val firstGpsTicks = gpsRows.firstOrNull()?.ticks ?: 0.0
        return LongArray(sensorRows.size) { i ->
            anchor + ((sensorRows[i].ticks - firstGpsTicks) * 10.0).toLong()
        }
    }

    /** Human-readable name for the clip list; falls back to the URI tail. */
    private fun queryDisplayName(uri: Uri): String {
        val ctx = getApplication<Application>().applicationContext
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) { /* fall through */ }
        return uri.lastPathSegment ?: "video"
    }

    /** Source file size in bytes for the output-size estimate; 0 if unknown. */
    private fun querySize(uri: Uri): Long {
        val ctx = getApplication<Application>().applicationContext
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
                }
        } catch (_: Exception) { /* fall through */ }
        return 0L
    }

    /**
     * Recompute the merged-output size estimate + the 2 GB WhatsApp guard from
     * the current clips + panel availability. No native "estimate output size"
     * exists for Media3 Transformer, so this scales the summed source bytes by
     * the film-length factor (intro/title/freeze/outro seconds charged at the
     * average clip bitrate — a safe over-estimate) and the panel-stack factor
     * (panels roughly double the frame height: `planOutput` sets the panel band
     * to the full source height). iOS uses AVFoundation's own estimator, so the
     * two won't match to the byte, but both flag a film that lands over 2 GB.
     */
    private fun refreshEstimate() {
        val s = _state.value
        val clips = s.clips
        val totalBytes = clips.sumOf { it.sizeBytes }
        val clipMs = clips.sumOf { maxOf(it.durationMillis, 0L) }
        if (clips.isEmpty() || totalBytes <= 0L || clipMs <= 0L) {
            _state.update { it.copy(estimatedExportBytes = null, exceedsShareLimit = false) }
            return
        }
        val filmMs = clipMs + clips.size * (2500L + 3000L) + 3000L + 5000L
        val filmFactor = filmMs.toDouble() / clipMs.toDouble()
        val panelFactor = if (s.sensorsReady) 2.0 else 1.0
        val est = (totalBytes.toDouble() * filmFactor * panelFactor).toLong()
        _state.update {
            it.copy(
                estimatedExportBytes = est,
                exceedsShareLimit = est >= SHARE_SIZE_LIMIT_BYTES,
            )
        }
    }

    companion object {
        /** WhatsApp refuses to send a video larger than 2 GB. */
        const val SHARE_SIZE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
    }
}

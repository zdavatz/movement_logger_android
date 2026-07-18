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
                        )
                    }.sortedBy { it.creationTimeMillis ?: Long.MAX_VALUE }
                }
                _state.update { it.copy(clips = infos, loading = false) }
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
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearExportState() {
        _state.update { it.copy(exportState = ExportState.Idle) }
    }

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
}

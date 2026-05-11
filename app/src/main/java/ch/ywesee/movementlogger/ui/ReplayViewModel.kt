package ch.ywesee.movementlogger.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.ywesee.movementlogger.data.Baro
import ch.ywesee.movementlogger.data.CsvParsers
import ch.ywesee.movementlogger.data.Fusion
import ch.ywesee.movementlogger.data.FusionHeight
import ch.ywesee.movementlogger.data.GpsMath
import ch.ywesee.movementlogger.data.GpsRow
import ch.ywesee.movementlogger.data.GpsTime
import ch.ywesee.movementlogger.data.ReplayTrim
import ch.ywesee.movementlogger.data.SensorRow
import ch.ywesee.movementlogger.data.VideoExporter
import ch.ywesee.movementlogger.data.VideoMetadata
import ch.ywesee.movementlogger.data.VideoMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReplayUiState(
    val videoUri: Uri? = null,
    val videoMeta: VideoMetadata? = null,
    val sensorFile: File? = null,
    val gpsFile: File? = null,
    val sensorRows: List<SensorRow> = emptyList(),
    val gpsRows: List<GpsRow> = emptyList(),
    val gpsAnchorUtcMillis: Long? = null,
    /** Smoothed position-derived speed per GPS row, km/h. */
    val speedSmoothedKmh: DoubleArray = DoubleArray(0),
    /** Absolute UTC ms per GPS row (parsed from `utc` against today's date). -1 if unparseable. */
    val gpsAbsTimesMs: LongArray = LongArray(0),
    /** Drift-corrected nose angle (Nasenwinkel) per sensor row, degrees. */
    val pitchDeg: DoubleArray = DoubleArray(0),
    /** Baro-only height above water per sensor row, metres. */
    val baroHeightM: DoubleArray = DoubleArray(0),
    /** Complementary-fused baro + IMU height per sensor row, metres. */
    val fusedHeightM: DoubleArray = DoubleArray(0),
    /** Absolute UTC ms per sensor row, derived from ticks against the GPS anchor. */
    val sensorAbsTimesMs: LongArray = LongArray(0),
    /** Sampling rate of the sensor stream, Hz (auto-detected from median tick delta). */
    val sampleHz: Double = 0.0,
    val loading: Boolean = false,
    val computing: Boolean = false,
    val error: String? = null,
    val exportState: ExportState = ExportState.Idle,
)

sealed class ExportState {
    data object Idle : ExportState()
    data class Running(val progress: Float) : ExportState()
    data class Done(val uri: Uri) : ExportState()
    data class Failed(val message: String) : ExportState()
}

class ReplayViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReplayUiState())
    val state: StateFlow<ReplayUiState> = _state.asStateFlow()

    /** `Sens*`, `Gps*`, `Bat*` files already saved to the app-private external dir. */
    fun listLocalRecordings(): List<File> {
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * Video files (.mov / .mp4 / .m4v) sitting in the app-private external
     * dir. Lets users (and the test harness) skip the system Photo Picker
     * entirely — just adb-push a video next to the CSVs.
     */
    fun listLocalVideos(): List<File> {
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && isVideoFile(it.name) }
            ?.sortedBy { it.name } ?: emptyList()
    }

    private fun isVideoFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mov") || n.endsWith(".mp4") || n.endsWith(".m4v")
    }

    /** Same as `pickVideo(Uri)` but for a file already on local storage. */
    fun pickLocalVideo(file: File) = pickVideo(Uri.fromFile(file))

    fun pickVideo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val meta = withContext(Dispatchers.IO) {
                VideoMetadataReader.read(getApplication(), uri)
            }
            _state.update { it.copy(videoUri = uri, videoMeta = meta, loading = false) }
            // Re-anchor any already-loaded GPS rows to the video's session date.
            // hhmmss.ss strings have no date, so the GPS abs-time series we
            // computed earlier was anchored to "today" — which is only correct
            // if the user records and replays the same day.
            if (_state.value.gpsRows.isNotEmpty()) reanchorGpsTimes()
        }
    }

    /** Year/month/day to use when interpreting `hhmmss.ss` UTC strings. */
    private fun sessionDate(): Triple<Int, Int, Int> {
        val creation = _state.value.videoMeta?.creationTimeMillis
        return if (creation != null) GpsTime.utcYmdFromMillis(creation) else GpsTime.todayUtc()
    }

    private fun reanchorGpsTimes() {
        viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value
            val (y, mo, d) = sessionDate()
            val rows = s.gpsRows
            val times = LongArray(rows.size) { i -> GpsTime.toUtcMillis(rows[i].utc, y, mo, d) ?: -1L }
            val anchor = times.firstOrNull { it >= 0L }
            _state.update { it.copy(gpsAbsTimesMs = times, gpsAnchorUtcMillis = anchor) }
            // Sensor abs times are derived from the GPS anchor + tick offset,
            // so they need a refresh too.
            if (s.sensorRows.isNotEmpty() && anchor != null) {
                val firstGpsTicks = s.gpsRows.first().ticks
                val sensorTimes = LongArray(s.sensorRows.size) { i ->
                    val deltaTicks = s.sensorRows[i].ticks - firstGpsTicks
                    anchor + (deltaTicks * 10.0).toLong()
                }
                _state.update { it.copy(sensorAbsTimesMs = sensorTimes) }
            }
        }
    }

    fun pickSensorCsv(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = withContext(Dispatchers.IO) { CsvParsers.parseSensorFile(file) }
                _state.update { it.copy(sensorFile = file, sensorRows = rows, loading = false) }
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
                val rows = withContext(Dispatchers.IO) { CsvParsers.parseGpsFile(file) }
                // Precompute the speed series + per-row absolute UTC. Done on
                // IO since smoothing a 1800-row session is fine but isn't
                // something to block the main thread on.
                val (y, mo, d) = sessionDate()
                val (smoothed, absTimes, anchor) = withContext(Dispatchers.IO) {
                    val raw = GpsMath.positionDerivedSpeedKmh(rows)
                    val cleaned = GpsMath.rejectAccOutliers(rows, raw)
                    val smooth = GpsMath.smoothSpeedKmh(cleaned)
                    val times = LongArray(rows.size) { i ->
                        GpsTime.toUtcMillis(rows[i].utc, y, mo, d) ?: -1L
                    }
                    val firstAnchor = times.firstOrNull { it >= 0L }
                    Triple(smooth, times, firstAnchor)
                }
                _state.update {
                    it.copy(
                        gpsFile = file,
                        gpsRows = rows,
                        gpsAnchorUtcMillis = anchor,
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

    /**
     * Run the full fusion / baro / height pipeline once both sensor and GPS
     * CSVs are loaded. Quaternions, drift-corrected pitch, GPS-anchored
     * baro height, complementary-fused height, and per-sensor-row absolute
     * UTC all land in state when this returns.
     */
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
                        s.sensorRows, s.gpsRows, s.speedSmoothedKmh, baseTicks
                    )
                    val fusedH = FusionHeight.fusedHeightM(s.sensorRows, quats, baroH, sampleHz)

                    // Per-sensor-row absolute UTC: anchor onto the first GPS fix
                    // by tick offset (1 tick = 10 ms).
                    val anchor = s.gpsAnchorUtcMillis ?: 0L
                    val firstGpsTicks = s.gpsRows.first().ticks
                    val sensorTimes = LongArray(s.sensorRows.size) { i ->
                        val deltaTicks = s.sensorRows[i].ticks - firstGpsTicks
                        anchor + (deltaTicks * 10.0).toLong()
                    }
                    FusionResults(pitch, baroH, fusedH, sensorTimes, sampleHz)
                }
                _state.update {
                    it.copy(
                        pitchDeg = result.pitch,
                        baroHeightM = result.baroH,
                        fusedHeightM = result.fusedH,
                        sensorAbsTimesMs = result.sensorTimes,
                        sampleHz = result.sampleHz,
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
        val sampleHz: Double,
    )

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearExportState() {
        _state.update { it.copy(exportState = ExportState.Idle) }
    }

    /**
     * Render a combined rider + sensor-panels video clipped to the video's
     * time window and save it to MediaStore (Photos app). Requires a video
     * with a parseable `creation_time` and both CSVs loaded with fusion
     * complete.
     */
    fun exportCombinedVideo() {
        val s = _state.value
        val uri = s.videoUri
        val creationMs = s.videoMeta?.creationTimeMillis
        if (uri == null || creationMs == null) {
            _state.update {
                it.copy(exportState = ExportState.Failed("Video needs creation_time metadata"))
            }
            return
        }
        if (s.sensorRows.isEmpty() || s.gpsRows.isEmpty() || s.pitchDeg.isEmpty()) {
            _state.update {
                it.copy(exportState = ExportState.Failed("Load Sens + GPS CSVs first"))
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(exportState = ExportState.Running(0f)) }
            try {
                val ctx = getApplication<Application>().applicationContext
                val source = withContext(Dispatchers.IO) { VideoExporter.probeSource(ctx, uri) }
                val windowStart = creationMs
                val windowEnd = creationMs + source.durationMs
                val trimmed = withContext(Dispatchers.Default) {
                    ReplayTrim.trimToWindow(
                        gpsRows = s.gpsRows,
                        gpsAbsTimesMs = s.gpsAbsTimesMs,
                        speedSmoothedKmh = s.speedSmoothedKmh,
                        sensorAbsTimesMs = s.sensorAbsTimesMs,
                        pitchDeg = s.pitchDeg,
                        baroHeightM = s.baroHeightM,
                        fusedHeightM = s.fusedHeightM,
                        startMs = windowStart,
                        endMs = windowEnd,
                    )
                }
                if (trimmed.gpsRows.isEmpty() || trimmed.pitchDeg.isEmpty()) {
                    throw IllegalStateException(
                        "No sensor or GPS rows overlap the video window — check date alignment.",
                    )
                }
                val displayName = "MovementLogger_${System.currentTimeMillis()}.mp4"
                val savedUri = VideoExporter.export(
                    context = ctx,
                    sourceUri = uri,
                    sourceSize = source,
                    trimmed = trimmed,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd,
                    displayName = displayName,
                ) { p ->
                    _state.update { it.copy(exportState = ExportState.Running(p)) }
                }
                _state.update { it.copy(exportState = ExportState.Done(savedUri)) }
            } catch (e: Exception) {
                _state.update { it.copy(exportState = ExportState.Failed(e.message ?: "export failed")) }
            }
        }
    }
}

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
import ch.ywesee.movementlogger.data.SensorRow
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
)

class ReplayViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReplayUiState())
    val state: StateFlow<ReplayUiState> = _state.asStateFlow()

    /** `Sens*`, `Gps*`, `Bat*` files already saved to the app-private external dir. */
    fun listLocalRecordings(): List<File> {
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    fun pickVideo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val meta = withContext(Dispatchers.IO) {
                VideoMetadataReader.read(getApplication(), uri)
            }
            _state.update { it.copy(videoUri = uri, videoMeta = meta, loading = false) }
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
                val (smoothed, absTimes, anchor) = withContext(Dispatchers.IO) {
                    val raw = GpsMath.positionDerivedSpeedKmh(rows)
                    val cleaned = GpsMath.rejectAccOutliers(rows, raw)
                    val smooth = GpsMath.smoothSpeedKmh(cleaned)
                    val (y, mo, d) = GpsTime.todayUtc()
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
}

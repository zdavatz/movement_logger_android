package ch.ywesee.movementlogger.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.ywesee.movementlogger.data.SessionData
import ch.ywesee.movementlogger.data.SessionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Loader progress shown while a session is read. */
data class SessionLoadProgress(val file: String, val done: Long, val total: Long)

/**
 * State behind the Session screen — one recorded session from a
 * `SENSnnn.CSV` + `GPSnnn.CSV` pair, no video. Cursor, view range and
 * toggles live here (Compose state) so they survive switching tabs.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    var sensorFile by mutableStateOf<File?>(null)
        private set
    var gpsFile by mutableStateOf<File?>(null)
        private set
    var data by mutableStateOf<SessionData?>(null)
        private set
    var progress by mutableStateOf<SessionLoadProgress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Cursor, seconds from session start. */
    var cursorS by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var speedIdx by mutableIntStateOf(1)

    /** Visible chart range (seconds); shared by every chart. */
    var viewLo by mutableDoubleStateOf(0.0)
    var viewHi by mutableDoubleStateOf(1.0)

    var follow by mutableStateOf(false)
    var satellite by mutableStateOf(false)
    /** Bumped by "Fit" — the map re-fits the camera when it changes. */
    var fitRequest by mutableIntStateOf(0)

    var showSpeed by mutableStateOf(true)
    var showAcc by mutableStateOf(true)
    var showAngles by mutableStateOf(true)
    var showHeight by mutableStateOf(true)
    var showGyro by mutableStateOf(false)

    private var loadJob: Job? = null

    val loading: Boolean get() = progress != null

    /** `Sens*` / `Gps*` CSVs in the app-private dir (where Sync saves them). */
    fun listRecordings(): List<File> {
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile && (isSensCsv(it.name) || isGpsCsv(it.name)) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /** Pick the sensor file and auto-pick its GPS twin (`SENS003` → `GPS003`). */
    fun pickSensor(f: File) {
        sensorFile = f
        val gpsNames = listRecordings().filter { isGpsCsv(it.name) }
        gpsFile = SessionLoader.matchingGpsName(f.name, gpsNames.map { it.name })
            ?.let { n -> gpsNames.first { it.name == n } }
    }

    fun pickGps(f: File?) {
        gpsFile = f
    }

    fun clearError() {
        error = null
    }

    /** Close the loaded session and go back to the file picker. */
    fun close() {
        playing = false
        data = null
    }

    fun load() {
        val sens = sensorFile ?: return
        val gps = gpsFile
        loadJob?.cancel()
        error = null
        playing = false
        progress = SessionLoadProgress(sens.name, 0, sens.length())
        loadJob = viewModelScope.launch {
            try {
                val t0 = System.nanoTime()
                val d = withContext(Dispatchers.IO) {
                    SessionLoader.load(sens, gps) { file, done, total ->
                        // Posted, so the main thread sees a consistent value.
                        viewModelScope.launch { progress = SessionLoadProgress(file, done, total) }
                    }
                }
                Log.i(TAG, "loaded ${sens.name}: ${d.sensT.size} sens / ${d.gpsT.size} gps rows in " +
                    "${(System.nanoTime() - t0) / 1_000_000} ms")
                // A whole session in RAM is ~60 MB of arrays; drop the old
                // one before the new one is referenced from Compose.
                data = null
                cursorS = 0.0
                viewLo = 0.0
                viewHi = d.durationS.coerceAtLeast(0.1)
                fitRequest++
                data = d
            } catch (e: OutOfMemoryError) {
                error = "Not enough memory for ${sens.name}"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                progress = null
            }
        }
    }

    /** Zoom the shared chart range by [factor] (< 1 = in) around fraction [at]. */
    fun zoomView(factor: Double, at: Double) {
        val d = data ?: return
        val dur = d.durationS.coerceAtLeast(0.1)
        val span = viewHi - viewLo
        val newSpan = (span * factor).coerceIn(minOf(5.0, dur), dur)
        val pivot = viewLo + span * at
        setView(pivot - newSpan * at, newSpan)
    }

    /** Pan the chart range by [fraction] of its width (positive = later). */
    fun panView(fraction: Double) {
        val span = viewHi - viewLo
        setView(viewLo + span * fraction, span)
    }

    fun resetView() {
        val d = data ?: return
        setView(0.0, d.durationS.coerceAtLeast(0.1))
    }

    /** Playback keeps the cursor on screen when zoomed in. */
    fun keepCursorVisible() {
        val span = viewHi - viewLo
        if (cursorS > viewHi || cursorS < viewLo) setView(cursorS - span * 0.1, span)
    }

    private fun setView(lo: Double, span: Double) {
        val dur = data?.durationS?.coerceAtLeast(0.1) ?: return
        val l = lo.coerceIn(0.0, (dur - span).coerceAtLeast(0.0))
        viewLo = l
        viewHi = (l + span).coerceAtMost(dur)
    }

    companion object {
        private const val TAG = "SessionViewModel"
        val PLAY_SPEEDS = doubleArrayOf(1.0, 5.0, 20.0, 60.0, 300.0)
    }
}

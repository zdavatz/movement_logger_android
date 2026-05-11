package ch.ywesee.movementlogger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ch.ywesee.movementlogger.data.formatLocalTime
import java.io.File
import kotlinx.coroutines.delay

/**
 * Replay screen — pick a video and one or two CSV files saved by the
 * Sync tab, then watch them play back time-synced. This commit lands the
 * video player, file pickers, and CSV parsing wiring; the actual data
 * overlays come in the next slice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(vm: ReplayViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) vm.pickVideo(uri) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(state.videoUri) {
        val uri = state.videoUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    // Poll the playhead at ~30 fps. Cheap; produceState restarts when `player`
    // changes (won't, in this screen).
    val playheadMs by produceState(initialValue = 0L, player) {
        while (true) {
            value = player.currentPosition
            delay(33L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Replay") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            VideoSurface(player = player, hasVideo = state.videoUri != null)
            Spacer(Modifier.height(12.dp))

            Row {
                Button(onClick = {
                    videoLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }) { Text(if (state.videoUri == null) "Pick video" else "Replace video") }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            RecordingPicker(vm = vm, state = state)

            if (state.loading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("loading…")
                }
            }

            state.error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(onClick = vm::clearError) { Text("Dismiss") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            AlignmentSummary(state)

            if (state.speedSmoothedKmh.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SpeedPanel(
                    smoothed = state.speedSmoothedKmh,
                    gpsAbsTimesMs = state.gpsAbsTimesMs,
                    videoCreationMs = state.videoMeta?.creationTimeMillis,
                    playheadMs = playheadMs,
                )
            }
            if (state.computing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("running fusion + baro + nose-angle…")
                }
            }
            if (state.pitchDeg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                PitchPanel(
                    pitchDeg = state.pitchDeg,
                    sensorAbsTimesMs = state.sensorAbsTimesMs,
                    videoCreationMs = state.videoMeta?.creationTimeMillis,
                    playheadMs = playheadMs,
                )
            }
            if (state.fusedHeightM.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HeightPanel(
                    baroM = state.baroHeightM,
                    fusedM = state.fusedHeightM,
                    sensorAbsTimesMs = state.sensorAbsTimesMs,
                    videoCreationMs = state.videoMeta?.creationTimeMillis,
                    playheadMs = playheadMs,
                )
            }
            if (state.gpsRows.size > 1) {
                Spacer(Modifier.height(12.dp))
                GpsTrackPanel(
                    gpsRows = state.gpsRows,
                    gpsAbsTimesMs = state.gpsAbsTimesMs,
                    videoCreationMs = state.videoMeta?.creationTimeMillis,
                    playheadMs = playheadMs,
                )
            }
        }
    }
}

@Composable
private fun SpeedPanel(
    smoothed: DoubleArray,
    gpsAbsTimesMs: LongArray,
    videoCreationMs: Long?,
    playheadMs: Long,
) {
    val n = smoothed.size
    val maxV = (smoothed.maxOrNull() ?: 0.0).coerceAtLeast(5.0)

    // Map video playhead → GPS row index by absolute UTC. When the video has
    // no creation_time, the cursor is hidden — the user can still see the
    // overall speed shape without alignment.
    val cursorIdx: Int = if (videoCreationMs != null && gpsAbsTimesMs.isNotEmpty()) {
        nearestIndexByTime(gpsAbsTimesMs, videoCreationMs + playheadMs)
    } else -1
    val currentSpeed = if (cursorIdx in 0 until n) smoothed[cursorIdx] else 0.0

    val lineColor = MaterialTheme.colorScheme.primary
    Column {
        Text("Speed (km/h)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (n < 2) return@Canvas
                val w = size.width
                val h = size.height
                val path = Path()
                for (i in 0 until n) {
                    val x = i.toFloat() / (n - 1) * w
                    val y = h - (smoothed[i] / maxV * h).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
                if (cursorIdx in 0 until n) {
                    val cx = cursorIdx.toFloat() / (n - 1) * w
                    drawLine(
                        color = Color(0xFFD32F2F),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            // Top-left current/max labels.
            Column(
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    "now %.1f".format(currentSpeed),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "max %.1f".format(maxV),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (videoCreationMs == null) {
            Text(
                "Video has no creation_time — cursor hidden. Future slice: manual offset slider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PitchPanel(
    pitchDeg: DoubleArray,
    sensorAbsTimesMs: LongArray,
    videoCreationMs: Long?,
    playheadMs: Long,
) {
    val n = pitchDeg.size
    if (n < 2) return
    var minV = Double.POSITIVE_INFINITY
    var maxV = Double.NEGATIVE_INFINITY
    for (v in pitchDeg) { if (v < minV) minV = v; if (v > maxV) maxV = v }
    // Keep zero visible — pad symmetrically around 0.
    val absMax = maxOf(kotlin.math.abs(minV), kotlin.math.abs(maxV), 5.0)
    val cursorIdx = if (videoCreationMs != null && sensorAbsTimesMs.isNotEmpty()) {
        nearestIndexByTime(sensorAbsTimesMs, videoCreationMs + playheadMs)
    } else -1
    val currentPitch = if (cursorIdx in 0 until n) pitchDeg[cursorIdx] else 0.0

    val lineColor = MaterialTheme.colorScheme.secondary
    val zeroColor = MaterialTheme.colorScheme.outline
    Column {
        Text("Pitch / Nasenwinkel (°)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val zeroY = h / 2f
                drawLine(zeroColor, Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1f)
                val path = Path()
                for (i in 0 until n) {
                    val x = i.toFloat() / (n - 1) * w
                    val y = zeroY - (pitchDeg[i] / absMax * (h / 2)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
                if (cursorIdx in 0 until n) {
                    val cx = cursorIdx.toFloat() / (n - 1) * w
                    drawLine(
                        color = Color(0xFFD32F2F),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text(
                    "now %+.1f°".format(currentPitch),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
                Text(
                    "±%.0f°".format(absMax),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeightPanel(
    baroM: DoubleArray,
    fusedM: DoubleArray,
    sensorAbsTimesMs: LongArray,
    videoCreationMs: Long?,
    playheadMs: Long,
) {
    val n = fusedM.size
    if (n < 2) return
    var minV = Double.POSITIVE_INFINITY
    var maxV = Double.NEGATIVE_INFINITY
    for (v in baroM) { if (v < minV) minV = v; if (v > maxV) maxV = v }
    for (v in fusedM) { if (v < minV) minV = v; if (v > maxV) maxV = v }
    if (maxV - minV < 0.2) { minV -= 0.1; maxV += 0.1 }
    val span = maxV - minV

    val cursorIdx = if (videoCreationMs != null && sensorAbsTimesMs.isNotEmpty()) {
        nearestIndexByTime(sensorAbsTimesMs, videoCreationMs + playheadMs)
    } else -1
    val curBaro = if (cursorIdx in baroM.indices) baroM[cursorIdx] else 0.0
    val curFused = if (cursorIdx in fusedM.indices) fusedM[cursorIdx] else 0.0

    val baroColor = MaterialTheme.colorScheme.outline
    val fusedColor = MaterialTheme.colorScheme.primary
    Column {
        Text("Height above water (m)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                fun draw(arr: DoubleArray, color: Color, stroke: Float) {
                    if (arr.size < 2) return
                    val path = Path()
                    for (i in 0 until arr.size) {
                        val x = i.toFloat() / (arr.size - 1) * w
                        val y = h - ((arr[i] - minV) / span * h).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = stroke))
                }
                draw(baroM, baroColor, 1.dp.toPx())
                draw(fusedM, fusedColor, 2.dp.toPx())

                if (cursorIdx in 0 until n) {
                    val cx = cursorIdx.toFloat() / (n - 1) * w
                    drawLine(
                        color = Color(0xFFD32F2F),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text(
                    "fused %+.2f m".format(curFused),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "baro  %+.2f m".format(curBaro),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "range %+.2f .. %+.2f".format(minV, maxV),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GpsTrackPanel(
    gpsRows: List<ch.ywesee.movementlogger.data.GpsRow>,
    gpsAbsTimesMs: LongArray,
    videoCreationMs: Long?,
    playheadMs: Long,
) {
    if (gpsRows.size < 2) return
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    for (r in gpsRows) {
        if (r.lat < minLat) minLat = r.lat
        if (r.lat > maxLat) maxLat = r.lat
        if (r.lon < minLon) minLon = r.lon
        if (r.lon > maxLon) maxLon = r.lon
    }
    // Aspect-correct longitude span by cos(meanLat) so 1° lon ≠ 1° lat.
    val meanLat = (minLat + maxLat) / 2.0
    val lonScale = kotlin.math.cos(Math.toRadians(meanLat))
    val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
    val lonSpan = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-9)

    val cursorIdx = if (videoCreationMs != null && gpsAbsTimesMs.isNotEmpty()) {
        nearestIndexByTime(gpsAbsTimesMs, videoCreationMs + playheadMs)
    } else -1

    val trackColor = MaterialTheme.colorScheme.tertiary
    Column {
        Text("GPS track", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Fit-to-canvas with uniform scale: pick the tighter of the two axes
                val scale = minOf(w / lonSpan, h / latSpan).toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val lonMid = (minLon + maxLon) / 2.0
                val latMid = (minLat + maxLat) / 2.0
                fun project(lat: Double, lon: Double): Offset {
                    val dx = ((lon - lonMid) * lonScale * scale).toFloat()
                    val dy = ((lat - latMid) * scale).toFloat()
                    // North up: subtract dy since canvas y grows down.
                    return Offset(cx + dx, cy - dy)
                }

                val path = Path()
                for (i in gpsRows.indices) {
                    val p = project(gpsRows[i].lat, gpsRows[i].lon)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color = trackColor, style = Stroke(width = 2.dp.toPx()))

                if (cursorIdx in gpsRows.indices) {
                    val p = project(gpsRows[cursorIdx].lat, gpsRows[cursorIdx].lon)
                    drawCircle(
                        color = Color(0xFFD32F2F),
                        radius = 6.dp.toPx(),
                        center = p,
                    )
                }
            }
        }
    }
}

/** Nearest-index search by absolute time. `arr` ascending, may contain -1 sentinels at edges. */
private fun nearestIndexByTime(arr: LongArray, target: Long): Int {
    val n = arr.size
    if (n == 0) return -1
    // Skip leading sentinels (-1 from un-parseable utc strings).
    var lo = 0
    while (lo < n && arr[lo] < 0) lo++
    if (lo >= n) return -1
    var hi = n - 1
    if (target <= arr[lo]) return lo
    if (target >= arr[hi]) return hi
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (arr[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

@Composable
private fun VideoSurface(player: ExoPlayer, hasVideo: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
    ) {
        if (hasVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "Pick a video to begin",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingPicker(vm: ReplayViewModel, state: ReplayUiState) {
    val recordings = remember(state.sensorFile, state.gpsFile) { vm.listLocalRecordings() }
    val sensorCandidates = recordings.filter { isSensCsv(it.name) }
    val gpsCandidates = recordings.filter { isGpsCsv(it.name) }

    if (recordings.isEmpty()) {
        Text(
            "No CSVs in this app's storage yet. Use the Sync tab to download some first.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text("Sensor CSV", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    FileChooserList(
        files = sensorCandidates,
        selected = state.sensorFile,
        empty = "no Sens*.csv downloaded",
        onPick = vm::pickSensorCsv,
    )
    Spacer(Modifier.height(8.dp))
    Text("GPS CSV", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    FileChooserList(
        files = gpsCandidates,
        selected = state.gpsFile,
        empty = "no Gps*.csv downloaded",
        onPick = vm::pickGpsCsv,
    )
}

@Composable
private fun FileChooserList(
    files: List<File>,
    selected: File?,
    empty: String,
    onPick: (File) -> Unit,
) {
    if (files.isEmpty()) {
        Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        items(files, key = { it.absolutePath }) { f ->
            val isSelected = f == selected
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(f.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            humanBytesShort(f.length()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { onPick(f) }) {
                        Text(if (isSelected) "Reload" else "Load")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignmentSummary(state: ReplayUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val videoLine = state.videoMeta?.creationTimeMillis?.let {
            "video creation: ${formatLocalTime(it)} (local)"
        } ?: "video creation: —"
        val gpsLine = state.gpsAnchorUtcMillis?.let {
            "gps t0:          ${formatLocalTime(it)} (local, today's date)"
        } ?: "gps t0:          —"
        val sensorLine = state.sensorRows.size.let { n ->
            if (n == 0) "sensor rows:     —" else "sensor rows:     $n"
        }
        val gpsRowsLine = state.gpsRows.size.let { n ->
            if (n == 0) "gps rows:        —" else "gps rows:        $n"
        }
        Text("Alignment", fontWeight = FontWeight.SemiBold)
        Text(videoLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(gpsLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sensorLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(gpsRowsLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun isSensCsv(name: String): Boolean {
    val n = name.lowercase()
    if (n.startsWith("._")) return false
    return n.startsWith("sens") && n.endsWith(".csv")
}

private fun isGpsCsv(name: String): Boolean {
    val n = name.lowercase()
    if (n.startsWith("._")) return false
    return n.startsWith("gps") && n.endsWith(".csv")
}

private fun humanBytesShort(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / (1024.0 * 1024.0))
}

package ch.ywesee.movementlogger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
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
        }
    }
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
